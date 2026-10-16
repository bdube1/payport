package zw.co.payport.gateway.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Screening lists and matching.
 *
 * Lists: manual lists (compliance loads them) and the UN Consolidated List (UnSanctionsList keeps
 * it current). Each load is a new version that replaces the previous one atomically.
 *
 * Outcome of a screening (recorded in screening_runs, with the list versions used):
 *   CLEAR            every required list is current and nothing matched
 *   POTENTIAL_MATCH  something matched (whatever the list state)
 *   INCOMPLETE       nothing matched, but a required list is unavailable, quarantined or outdated
 *                    (not checked within SCREENING.MAX_LIST_AGE_HOURS): never treated as CLEAR
 *
 * Matching: exact identifier (phone on the last 9 digits, document or registration number) or fuzzy
 * name (NameMatcher) at or above the minimum score; aliases the UN marks Low quality are penalised
 * by SCREENING.LOW_QUALITY_ALIAS_PENALTY. A subject cleared as a false positive for an entry is not
 * matched to it again.
 */
@Slf4j
@Service
public class ScreeningService {

    public record Entry(String listCode, String entryRef, String entityType, String name, List<String> aliases, List<String> identifiers,
                        String country, Map<String, Object> details) {
        public Entry(String listCode, String entryRef, String entityType, String name, List<String> aliases, List<String> identifiers, String country) {
            this(listCode, entryRef, entityType, name, aliases, identifiers, country, null);
        }
    }

    public record Match(String listCode, String entryRef, String listedName, double score, String matchedOn) {}

    public record Result(String outcome, List<Match> matches, Map<String, Object> listVersions, String incompleteReason, long runId) {
        public boolean clear() { return "CLEAR".equals(outcome); }
    }

    private final JdbcTemplate jdbc;
    private final ComplianceParameters params;
    private final ObjectMapper json = new ObjectMapper();
    private final org.springframework.transaction.support.TransactionTemplate own;

    public ScreeningService(JdbcTemplate jdbc, ComplianceParameters params, org.springframework.transaction.PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.params = params;
        this.own = new org.springframework.transaction.support.TransactionTemplate(tm);
        this.own.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public long createList(String code, String name, String source, String by) {
        if (code == null || !code.matches("[A-Z0-9_-]{2,40}")) throw new IllegalArgumentException("code: 2-40 of A-Z 0-9 _ -");
        if (name == null || name.isBlank() || source == null || source.isBlank()) throw new IllegalArgumentException("name and source are required");
        jdbc.update("INSERT INTO screening_lists (code, name, source, status, created_by, created_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                code, name.trim(), source.trim(), by, Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject("SELECT id FROM screening_lists WHERE code = ?", Long.class, code);
    }

    /** Loads a new version of a list (replaces the entries). Returns the new version. */
    @Transactional
    public int load(String code, List<Entry> entries, String by) {
        Map<String, Object> list = jdbc.queryForMap("SELECT id, version, status, list_kind FROM screening_lists WHERE code = ? FOR UPDATE", code);
        if (!"ACTIVE".equals(list.get("status"))) throw new IllegalStateException("List " + code + " is retired");
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("A list load needs at least one entry");
        long id = ((Number) list.get("id")).longValue();
        int version = ((Number) list.get("version")).intValue() + 1;
        List<Object[]> rows = new ArrayList<>();
        for (Entry e : entries) {
            if (e.name() == null || e.name().isBlank() || e.entryRef() == null || e.entryRef().isBlank()) throw new IllegalArgumentException("every entry needs entryRef and name");
            String type = e.entityType() == null ? "PERSON" : e.entityType().toUpperCase();
            if (!type.equals("PERSON") && !type.equals("ORG")) throw new IllegalArgumentException("entityType must be PERSON or ORG");
            List<String> ids = new ArrayList<>();
            if (e.identifiers() != null) for (String raw : e.identifiers()) { String n = NameMatcher.identifier(raw); if (n != null) ids.add(n); }
            String details;
            try {
                details = e.details() == null ? null : json.writeValueAsString(e.details());
            } catch (Exception ex) {
                details = null;
            }
            String aliases = e.aliases() == null || e.aliases().isEmpty() ? null : String.join("|", e.aliases());
            String idList = ids.isEmpty() ? null : String.join(",", ids);
            rows.add(new Object[]{id, version, e.entryRef().trim(), type, e.name().trim(),
                    aliases != null && aliases.length() > 60000 ? aliases.substring(0, 60000) : aliases,
                    idList != null && idList.length() > 500 ? idList.substring(0, 500) : idList, e.country(), details});
        }
        jdbc.batchUpdate("INSERT INTO screening_entries (list_id, list_version, entry_ref, entity_type, name, aliases, identifiers, country, details) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", rows);
        jdbc.update("DELETE FROM screening_entries WHERE list_id = ? AND list_version < ?", id, version);
        jdbc.update("UPDATE screening_lists SET version = ?, entry_count = ?, loaded_by = ?, loaded_at = ? WHERE id = ?",
                version, entries.size(), by, Timestamp.valueOf(LocalDateTime.now()), id);
        log.info("[SCREENING] {} v{} loaded by {} ({} entries)", code, version, by, entries.size());
        return version;
    }

    public List<Map<String, Object>> lists() {
        return jdbc.queryForList("SELECT id, code, name, source, version, entry_count, status, list_kind, required_for_clear, source_url, source_sha256, "
                + "source_generated_at, last_checked_at, last_check_status, last_error, loaded_by, loaded_at FROM screening_lists ORDER BY code");
    }

    /** Health of the lists: which required ones are current. Null reason = complete. */
    public String incompleteReason() {
        double maxAgeHours = params.number("SCREENING.MAX_LIST_AGE_HOURS");
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> l : jdbc.queryForList("SELECT code, version, entry_count, last_checked_at, list_kind FROM screening_lists "
                + "WHERE status = 'ACTIVE' AND required_for_clear = TRUE")) {
            String code = (String) l.get("code");
            if (((Number) l.get("version")).intValue() == 0 || ((Number) l.get("entry_count")).intValue() == 0) { problems.add(code + " not loaded"); continue; }
            if ("UN_AUTO".equals(l.get("list_kind"))) {
                Object checked = l.get("last_checked_at");
                LocalDateTime at = checked instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) checked;
                if (at == null || at.isBefore(LocalDateTime.now().minusMinutes((long) (maxAgeHours * 60)))) problems.add(code + " not checked within " + maxAgeHours + " h");
            }
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    /** Screens a subject, records the run, and returns CLEAR / POTENTIAL_MATCH / INCOMPLETE. */
    public Result screen(String subjectKey, String context, List<String> names, List<String> identifiers, double minScore) {
        List<Match> matches = match(subjectKey, names, identifiers, minScore);
        String incomplete = incompleteReason();
        Map<String, Object> versions = new LinkedHashMap<>();
        for (Map<String, Object> l : jdbc.queryForList("SELECT code, version, source_sha256 FROM screening_lists WHERE status = 'ACTIVE'")) {
            versions.put((String) l.get("code"), l.get("version") + (l.get("source_sha256") == null ? "" : "@" + ((String) l.get("source_sha256")).substring(0, 12)));
        }
        String outcome = !matches.isEmpty() ? "POTENTIAL_MATCH" : incomplete != null ? "INCOMPLETE" : "CLEAR";
        long runId = -1;
        try {
            String v = json.writeValueAsString(versions);
            String m = matches.isEmpty() ? null : json.writeValueAsString(matches);
            org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
            // Own transaction: the evidence of a screening survives even if the action it guarded is refused.
            own.executeWithoutResult(st -> jdbc.update(con -> {
                var ps = con.prepareStatement("INSERT INTO screening_runs (at, subject_key, context, outcome, min_score, list_versions, incomplete_reason, matches) "
                        + "VALUES (?,?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
                ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                ps.setString(2, subjectKey);
                ps.setString(3, context);
                ps.setString(4, outcome);
                ps.setDouble(5, minScore);
                ps.setString(6, v.length() > 1000 ? v.substring(0, 1000) : v);
                ps.setString(7, incomplete);
                ps.setString(8, m);
                return ps;
            }, keys));
            runId = keys.getKey() == null ? -1 : keys.getKey().longValue();
        } catch (Exception e) {
            log.error("[SCREENING] could not record screening run for {}: {}", subjectKey, e.getMessage());
        }
        return new Result(outcome, matches, versions, incomplete, runId);
    }

    /** Backwards-compatible: matches only (records a run with context ADHOC). */
    public List<Match> screen(String subjectKey, List<String> names, List<String> identifiers, double minScore) {
        return screen(subjectKey, "ADHOC", names, identifiers, minScore).matches();
    }

    List<Match> match(String subjectKey, List<String> names, List<String> identifiers, double minScore) {
        List<String> ids = new ArrayList<>();
        if (identifiers != null) for (String raw : identifiers) { String n = NameMatcher.identifier(raw); if (n != null) ids.add(n); }
        double lowPenalty = params.number("SCREENING.LOW_QUALITY_ALIAS_PENALTY");
        List<Match> out = new ArrayList<>();
        List<Map<String, Object>> entries = jdbc.queryForList("SELECT l.code, e.entry_ref, e.name, e.aliases, e.identifiers FROM screening_entries e "
                + "JOIN screening_lists l ON l.id = e.list_id AND l.version = e.list_version WHERE l.status = 'ACTIVE'");
        if (entries.isEmpty()) return out;
        List<Map<String, Object>> cleared = jdbc.queryForList("SELECT list_code, entry_ref FROM screening_suppressions WHERE subject_key = ?", subjectKey);
        for (Map<String, Object> e : entries) {
            String list = (String) e.get("code"), ref = (String) e.get("entry_ref");
            if (cleared.stream().anyMatch(c -> list.equals(c.get("list_code")) && ref.equals(c.get("entry_ref")))) continue;
            Match best = null;
            String entryIds = (String) e.get("identifiers");
            if (entryIds != null) {
                for (String id : ids) {
                    if (("," + entryIds + ",").contains("," + id + ",")) { best = new Match(list, ref, (String) e.get("name"), 1.0, "identifier " + id.substring(0, id.indexOf(':'))); break; }
                }
            }
            if (best == null && names != null) {
                List<String[]> listed = new ArrayList<>();
                listed.add(new String[]{(String) e.get("name"), "name"});
                if (e.get("aliases") != null) {
                    for (String a : ((String) e.get("aliases")).split("\\|")) {
                        listed.add(a.startsWith("LOW:") ? new String[]{a.substring(4), "low-quality alias"} : new String[]{a, "alias"});
                    }
                }
                for (String n : names) {
                    if (n == null || n.isBlank()) continue;
                    for (String[] l : listed) {
                        double s = NameMatcher.score(n, l[0]);
                        if ("low-quality alias".equals(l[1])) s = Math.round((s - lowPenalty) * 1000) / 1000.0;
                        if (s >= minScore && (best == null || s > best.score())) best = new Match(list, ref, (String) e.get("name"), s, l[1]);
                    }
                }
            }
            if (best != null) out.add(best);
        }
        return out;
    }

    /** After a false-positive decision: the subject is not alerted for these entries again. */
    public void suppress(String subjectKey, List<String[]> listAndEntryRefs, long caseId, String by) {
        for (String[] le : listAndEntryRefs) {
            jdbc.update("INSERT IGNORE INTO screening_suppressions (subject_key, list_code, entry_ref, case_id, decided_by, decided_at) VALUES (?,?,?,?,?,?)",
                    subjectKey, le[0], le[1], caseId, by, Timestamp.valueOf(LocalDateTime.now()));
        }
    }

    public List<Map<String, Object>> runs(String subjectKey) {
        return jdbc.queryForList("SELECT * FROM screening_runs WHERE subject_key = ? ORDER BY id DESC LIMIT 100", subjectKey);
    }
}
