package zw.co.payport.gateway.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Screening lists (sanctions, PEP, internal watchlists) and matching. Compliance officers create a
 * list and load its entries; each load is a new version that replaces the previous one atomically,
 * and pending/active merchants are re-screened against it. Matching: exact identifier (phone, ID or
 * registration number) or fuzzy name (NameMatcher) at or above minScore. A subject cleared as a
 * false positive for an entry (case decision) is not alerted for that entry again.
 */
@Slf4j
@Service
public class ScreeningService {

    public record Entry(String listCode, String entryRef, String entityType, String name, List<String> aliases, List<String> identifiers, String country) {}

    public record Match(String listCode, String entryRef, String listedName, double score, String matchedOn) {}

    private final JdbcTemplate jdbc;

    public ScreeningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        Map<String, Object> list = jdbc.queryForMap("SELECT id, version, status FROM screening_lists WHERE code = ? FOR UPDATE", code);
        if (!"ACTIVE".equals(list.get("status"))) throw new IllegalStateException("List " + code + " is retired");
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("A list load needs at least one entry");
        long id = ((Number) list.get("id")).longValue();
        int version = ((Number) list.get("version")).intValue() + 1;
        for (Entry e : entries) {
            if (e.name() == null || e.name().isBlank() || e.entryRef() == null || e.entryRef().isBlank()) throw new IllegalArgumentException("every entry needs entryRef and name");
            String type = e.entityType() == null ? "PERSON" : e.entityType().toUpperCase();
            if (!type.equals("PERSON") && !type.equals("ORG")) throw new IllegalArgumentException("entityType must be PERSON or ORG");
            List<String> ids = new ArrayList<>();
            if (e.identifiers() != null) for (String raw : e.identifiers()) { String n = NameMatcher.identifier(raw); if (n != null) ids.add(n); }
            jdbc.update("INSERT INTO screening_entries (list_id, list_version, entry_ref, entity_type, name, aliases, identifiers, country) VALUES (?,?,?,?,?,?,?,?)",
                    id, version, e.entryRef().trim(), type, e.name().trim(), e.aliases() == null ? null : String.join("|", e.aliases()),
                    ids.isEmpty() ? null : String.join(",", ids), e.country());
        }
        jdbc.update("DELETE FROM screening_entries WHERE list_id = ? AND list_version < ?", id, version);
        jdbc.update("UPDATE screening_lists SET version = ?, entry_count = ?, loaded_by = ?, loaded_at = ? WHERE id = ?",
                version, entries.size(), by, Timestamp.valueOf(LocalDateTime.now()), id);
        log.info("[SCREENING] {} v{} loaded by {} ({} entries)", code, version, by, entries.size());
        return version;
    }

    public List<Map<String, Object>> lists() {
        return jdbc.queryForList("SELECT id, code, name, source, version, entry_count, status, loaded_by, loaded_at FROM screening_lists ORDER BY code");
    }

    /**
     * Matches a subject (names and identifiers) against every active list, minus entries the subject
     * was already cleared for. Best match per entry.
     */
    public List<Match> screen(String subjectKey, List<String> names, List<String> identifiers, double minScore) {
        List<String> ids = new ArrayList<>();
        if (identifiers != null) for (String raw : identifiers) { String n = NameMatcher.identifier(raw); if (n != null) ids.add(n); }
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
                List<String> listed = new ArrayList<>();
                listed.add((String) e.get("name"));
                if (e.get("aliases") != null) listed.addAll(List.of(((String) e.get("aliases")).split("\\|")));
                for (String n : names) {
                    if (n == null || n.isBlank()) continue;
                    for (String l : listed) {
                        double s = NameMatcher.score(n, l);
                        if (s >= minScore && (best == null || s > best.score())) best = new Match(list, ref, (String) e.get("name"), s, "name");
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
}
