package zw.co.payport.gateway.risk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.platform.JobRunner;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The UN Security Council Consolidated List (https://scsanctions.un.org/resources/xml/en/consolidated.xml).
 *
 * Secure update:
 *  - HTTPS only; redirects followed manually and only to allow-listed hosts (the UN serves the file
 *    from an Azure blob host);
 *  - size cap; XML parsed with DTDs and external entities disabled (XXE-safe);
 *  - the file must be a CONSOLIDATED_LIST with a dateGenerated and at least one entry;
 *  - a version with more than SCREENING.MAX_ENTRY_DROP_PERCENT fewer entries than the active one is
 *    QUARANTINED, not activated (truncated or tampered file), and compliance is alerted;
 *  - an unchanged file (same SHA-256) only refreshes the "checked" time.
 * Every attempt is recorded (screening_list_fetches) and audited. The list counts as current only if
 * it was successfully checked within SCREENING.MAX_LIST_AGE_HOURS; otherwise screening is INCOMPLETE.
 */
@Slf4j
@Service
public class UnSanctionsList {

    public static final String CODE = "UN-CONSOLIDATED";

    public record Fetched(String outcome, Integer version, int entries, String sha256, String generatedAt, String error) {}

    private final JdbcTemplate jdbc;
    private final ScreeningService screening;
    private final ComplianceParameters params;
    private final ComplianceAudit audit;
    private final AlertStore alerts;
    private final JobRunner jobs;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RiskEngine engine;
    private final String url;
    private final Set<String> allowedHosts;
    private final long maxBytes;
    private final boolean enabled;
    private final boolean allowHttpForTests;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();

    public UnSanctionsList(JdbcTemplate jdbc, ScreeningService screening, ComplianceParameters params, ComplianceAudit audit, AlertStore alerts, JobRunner jobs,
                           @Value("${payport.screening.un.url:https://scsanctions.un.org/resources/xml/en/consolidated.xml}") String url,
                           @Value("${payport.screening.un.allowed-hosts:scsanctions.un.org,unsolprodfiles.blob.core.windows.net}") String allowedHosts,
                           @Value("${payport.screening.un.max-bytes:52428800}") long maxBytes,
                           @Value("${payport.screening.un.enabled:true}") boolean enabled,
                           @Value("${payport.screening.un.allow-http-for-tests:false}") boolean allowHttpForTests,
                           @Value("${payport.environment:production}") String environment) {
        this.jdbc = jdbc;
        this.screening = screening;
        this.params = params;
        this.audit = audit;
        this.alerts = alerts;
        this.jobs = jobs;
        this.url = url;
        this.allowedHosts = Set.of(allowedHosts.toLowerCase().split("\\s*,\\s*"));
        this.maxBytes = maxBytes;
        this.enabled = enabled;
        if (allowHttpForTests && zw.co.payport.gateway.platform.DeploymentEnvironment.isProduction(environment)) {
            throw new IllegalStateException("payport.screening.un.allow-http-for-tests is refused in production");
        }
        this.allowHttpForTests = allowHttpForTests;
    }

    @Scheduled(fixedDelayString = "${payport.screening.un.refresh-ms:3600000}", initialDelayString = "${payport.screening.un.initial-delay-ms:60000}")
    public void scheduledRefresh() {
        if (!enabled) return;
        JobRunner.RunRecord r = jobs.runExclusive("un-sanctions-refresh", 900, () -> refresh("SYSTEM:un-list-refresh").toString());
        if (r.outcome() == JobRunner.Outcome.FAILED) log.error("[SCREENING] UN list refresh failed: {}", r.summary());
    }

    /** Downloads, verifies and (if new and sane) activates the list. Never throws: the outcome says what happened. */
    public Fetched refresh(String actor) {
        LocalDateTime now = LocalDateTime.now();
        String host = null;
        byte[] body;
        try {
            Object[] got = download();
            body = (byte[]) got[0];
            host = (String) got[1];
        } catch (Exception e) {
            return record(now, "FAILED", host, null, null, null, null, null, e.getMessage(), actor);
        }
        String sha = sha256(body);
        Map<String, Object> list = jdbc.queryForMap("SELECT * FROM screening_lists WHERE code = ?", CODE);
        if (sha.equals(list.get("source_sha256"))) {
            jdbc.update("UPDATE screening_lists SET last_checked_at = ?, last_check_status = 'UNCHANGED', last_error = NULL WHERE code = ?", Timestamp.valueOf(now), CODE);
            return record(now, "UNCHANGED", host, (long) body.length, sha, (String) list.get("source_generated_at"), ((Number) list.get("entry_count")).intValue(), null, null, actor);
        }
        Parsed p;
        try {
            p = parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            return record(now, "FAILED", host, (long) body.length, sha, null, null, null, "not a valid UN consolidated list: " + e.getMessage(), actor);
        }
        int previous = ((Number) list.get("entry_count")).intValue();
        double maxDrop = params.number("SCREENING.MAX_ENTRY_DROP_PERCENT");
        if (previous > 0 && p.entries().size() < previous * (1 - maxDrop / 100.0)) {
            jdbc.update("UPDATE screening_lists SET last_check_status = 'QUARANTINED', last_error = ? WHERE code = ?",
                    "new file has " + p.entries().size() + " entries, active version has " + previous, CODE);
            alerts.record(new AlertStore.Hit("SCREENING_UNAVAILABLE", 1, "OBSERVE", "HIGH", "SYSTEM", "SYSTEM:SCREENING", "UN consolidated list", null, null,
                    "{\"reason\":\"quarantined: entry count dropped from " + previous + " to " + p.entries().size() + "\"}", false, "quarantine|" + sha));
            return record(now, "QUARANTINED", host, (long) body.length, sha, p.generatedAt(), p.entries().size(), null, "entry count dropped beyond " + maxDrop + "%", actor);
        }
        int version = screening.load(CODE, p.entries(), actor);
        jdbc.update("UPDATE screening_lists SET source_sha256 = ?, source_generated_at = ?, last_checked_at = ?, last_check_status = 'NEW_VERSION', last_error = NULL WHERE code = ?",
                sha, p.generatedAt(), Timestamp.valueOf(now), CODE);
        log.info("[SCREENING] UN consolidated list v{} activated: {} entries, generated {}, sha256 {}", version, p.entries().size(), p.generatedAt(), sha);
        Fetched f = record(now, "NEW_VERSION", host, (long) body.length, sha, p.generatedAt(), p.entries().size(), version, null, actor);
        // New designations must be checked against existing customers and merchants (Guideline 01-2025 s3.81).
        if (engine != null) {
            try {
                Map<String, Integer> r = engine.rescreenAll("RESCREEN_UN_V" + version);
                audit.record(actor, "RESCREEN_AFTER_LIST_UPDATE", "screening_list:" + CODE, r.toString());
            } catch (RuntimeException e) {
                log.error("[SCREENING] re-screening after UN v{} failed: {}", version, e.getMessage());
            }
        }
        return f;
    }

    private Fetched record(LocalDateTime at, String outcome, String host, Long bytes, String sha, String generated, Integer entries, Integer version, String error, String actor) {
        String e = error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error);
        jdbc.update("INSERT INTO screening_list_fetches (list_code, at, outcome, final_url_host, bytes, sha256, generated_at, entries, activated_version, error) VALUES (?,?,?,?,?,?,?,?,?,?)",
                CODE, Timestamp.valueOf(at), outcome, host, bytes, sha, generated, entries, version, e);
        if ("FAILED".equals(outcome)) {
            jdbc.update("UPDATE screening_lists SET last_check_status = 'FAILED', last_error = ? WHERE code = ?", e, CODE);
            log.warn("[SCREENING] UN list fetch failed: {}", e);
        }
        audit.record(actor, "SCREENING_LIST_FETCH_" + outcome, "screening_list:" + CODE,
                "{\"sha256\":\"" + sha + "\",\"entries\":" + entries + ",\"version\":" + version + ",\"generatedAt\":\"" + generated + "\",\"error\":\"" + (e == null ? "" : e.replace("\"", "'")) + "\"}");
        return new Fetched(outcome, version, entries == null ? 0 : entries, sha, generated, e);
    }

    /** Returns {body, finalHost}. */
    private Object[] download() throws Exception {
        URI u = URI.create(url);
        for (int hop = 0; hop < 4; hop++) {
            checkUri(u);
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(120)).header("Accept", "application/xml").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int s = r.statusCode();
            if (s >= 300 && s < 400) {
                String loc = r.headers().firstValue("Location").orElseThrow(() -> new IllegalStateException("redirect without Location"));
                r.body().close();
                u = u.resolve(loc);
                continue;
            }
            if (s != 200) {
                r.body().close();
                throw new IllegalStateException("HTTP " + s + " from " + u.getHost());
            }
            try (InputStream in = r.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > maxBytes) throw new IllegalStateException("file larger than " + maxBytes + " bytes");
                    out.write(buf, 0, n);
                }
                return new Object[]{out.toByteArray(), u.getHost()};
            }
        }
        throw new IllegalStateException("too many redirects");
    }

    private void checkUri(URI u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(allowHttpForTests && scheme.equals("http"))) throw new IllegalStateException("refusing non-HTTPS URL " + u.getHost());
        if (u.getHost() == null || !allowedHosts.contains(u.getHost().toLowerCase())) throw new IllegalStateException("host not allowed: " + u.getHost());
    }

    record Parsed(String generatedAt, List<ScreeningService.Entry> entries) {}

    /** Streaming, XXE-safe parse of the UN consolidated list XML. */
    static Parsed parse(InputStream in) throws Exception {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader x = f.createXMLStreamReader(in);
        String generated = null;
        List<ScreeningService.Entry> out = new ArrayList<>();
        boolean sawRoot = false;
        Map<String, String> cur = null;
        List<String> aliases = null, docs = null, nationalities = null, dobs = null;
        String type = null, aliasQuality = null, aliasName = null, docNumber = null, dobValue = null;
        StringBuilder text = new StringBuilder();
        List<String> stack = new ArrayList<>();
        while (x.hasNext()) {
            int ev = x.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = x.getLocalName();
                stack.add(n);
                text.setLength(0);
                if (stack.size() == 1) {
                    if (!"CONSOLIDATED_LIST".equals(n)) throw new IllegalStateException("root is " + n + ", expected CONSOLIDATED_LIST");
                    sawRoot = true;
                    generated = x.getAttributeValue(null, "dateGenerated");
                } else if ("INDIVIDUAL".equals(n) || "ENTITY".equals(n)) {
                    type = "INDIVIDUAL".equals(n) ? "PERSON" : "ORG";
                    cur = new LinkedHashMap<>();
                    aliases = new ArrayList<>(); docs = new ArrayList<>(); nationalities = new ArrayList<>(); dobs = new ArrayList<>();
                } else if (n.endsWith("_ALIAS")) {
                    aliasQuality = null; aliasName = null;
                } else if ("INDIVIDUAL_DOCUMENT".equals(n)) {
                    docNumber = null;
                } else if ("INDIVIDUAL_DATE_OF_BIRTH".equals(n)) {
                    dobValue = null;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                text.append(x.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = x.getLocalName();
                String t = text.toString().trim();
                String parent = stack.size() >= 2 ? stack.get(stack.size() - 2) : "";
                if (cur != null) {
                    if (("INDIVIDUAL".equals(parent) || "ENTITY".equals(parent)) && Set.of("DATAID", "FIRST_NAME", "SECOND_NAME", "THIRD_NAME", "FOURTH_NAME",
                            "UN_LIST_TYPE", "REFERENCE_NUMBER", "LISTED_ON", "NAME_ORIGINAL_SCRIPT").contains(n)) {
                        if (!t.isEmpty()) cur.put(n, t);
                    } else if ("QUALITY".equals(n) && parent.endsWith("_ALIAS")) {
                        aliasQuality = t;
                    } else if ("ALIAS_NAME".equals(n)) {
                        aliasName = t;
                    } else if (n.endsWith("_ALIAS") && aliasName != null && !aliasName.isEmpty()) {
                        aliases.add(("Low".equalsIgnoreCase(aliasQuality) ? "LOW:" : "") + aliasName);
                    } else if ("NUMBER".equals(n) && "INDIVIDUAL_DOCUMENT".equals(parent)) {
                        docNumber = t;
                    } else if ("INDIVIDUAL_DOCUMENT".equals(n) && docNumber != null && !docNumber.isEmpty()) {
                        docs.add(docNumber);
                    } else if ("VALUE".equals(n) && "NATIONALITY".equals(parent) && !t.isEmpty()) {
                        nationalities.add(t);
                    } else if (("DATE".equals(n) || "YEAR".equals(n)) && "INDIVIDUAL_DATE_OF_BIRTH".equals(parent) && !t.isEmpty()) {
                        dobValue = t;
                    } else if ("INDIVIDUAL_DATE_OF_BIRTH".equals(n) && dobValue != null) {
                        dobs.add(dobValue);
                    } else if ("INDIVIDUAL".equals(n) || "ENTITY".equals(n)) {
                        String name = String.join(" ", List.of(cur.getOrDefault("FIRST_NAME", ""), cur.getOrDefault("SECOND_NAME", ""),
                                cur.getOrDefault("THIRD_NAME", ""), cur.getOrDefault("FOURTH_NAME", ""))).trim().replaceAll("\\s+", " ");
                        String ref = cur.getOrDefault("REFERENCE_NUMBER", cur.get("DATAID"));
                        if (!name.isEmpty() && ref != null) {
                            if (cur.containsKey("NAME_ORIGINAL_SCRIPT")) aliases.add(cur.get("NAME_ORIGINAL_SCRIPT"));
                            Map<String, Object> details = new LinkedHashMap<>();
                            details.put("dataId", cur.get("DATAID"));
                            details.put("unListType", cur.get("UN_LIST_TYPE"));
                            details.put("listedOn", cur.get("LISTED_ON"));
                            details.put("nationality", nationalities);
                            details.put("dateOfBirth", dobs);
                            out.add(new ScreeningService.Entry(CODE, ref, type, name, aliases, docs, null, details));
                        }
                        cur = null;
                    }
                }
                stack.remove(stack.size() - 1);
                text.setLength(0);
            }
        }
        if (!sawRoot) throw new IllegalStateException("empty document");
        if (generated == null || generated.isBlank()) throw new IllegalStateException("missing dateGenerated");
        if (out.isEmpty()) throw new IllegalStateException("no entries");
        return new Parsed(generated, out);
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Map<String, Object>> fetches() {
        return jdbc.queryForList("SELECT * FROM screening_list_fetches WHERE list_code = ? ORDER BY id DESC LIMIT 100", CODE);
    }
}
