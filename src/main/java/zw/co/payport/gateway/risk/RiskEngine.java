package zw.co.payport.gateway.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transaction-monitoring rules, evaluated when a payment is initiated (before the customer pays).
 *
 *   OFF       not evaluated
 *   OBSERVE   a hit raises an alert; the payment goes ahead (default for every rule)
 *   ENFORCE   a hit raises an alert; if the rule's action is BLOCK the payment is refused
 *
 * Rule types: VELOCITY_COUNT, VELOCITY_AMOUNT, STRUCTURING, LARGE_TRANSACTION, SANCTIONS.
 * Rules are read from risk_rules (changed only through two-person approval, ComplianceService).
 * A failure inside the engine never blocks a payment: it is logged and the payment proceeds.
 */
@Slf4j
@Service
public class RiskEngine {

    public record Payment(String reference, String customerPhone, String customerName, long amountMinor, String currency,
                          String productCode, String recipient) {}

    public record Rule(String code, String type, JsonNode params, String mode, String action, String severity, int version, boolean approved) {}

    private final JdbcTemplate jdbc;
    private final AlertStore alerts;
    private final ScreeningService screening;
    private final ObjectMapper json = new ObjectMapper();
    private final ComplianceParameters params;

    public RiskEngine(JdbcTemplate jdbc, AlertStore alerts, ScreeningService screening, ComplianceParameters params) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.screening = screening;
        this.params = params;
    }

    public List<Rule> rules() {
        return jdbc.query("SELECT * FROM risk_rules ORDER BY code", (rs, i) -> {
            try {
                return new Rule(rs.getString("code"), rs.getString("rule_type"), json.readTree(rs.getString("params")), rs.getString("mode"),
                        rs.getString("action"), rs.getString("severity"), rs.getInt("version"), rs.getString("approved_by") != null);
            } catch (Exception e) {
                throw new IllegalStateException("bad params for rule " + rs.getString("code"), e);
            }
        });
    }

    /** Evaluates every active rule for a payment about to be initiated. Throws RiskDeclinedException to refuse it. */
    public void evaluate(Payment p) {
        List<Rule> active;
        try {
            active = rules().stream().filter(r -> !"OFF".equals(r.mode())).toList();
        } catch (RuntimeException e) {
            log.error("[RISK] rules unavailable, payment {} not evaluated: {}", p.reference(), e.getMessage());
            return;
        }
        if (p.customerName() == null && p.customerPhone() != null) {
            // A registered customer's name, for name screening (guests are screened on the phone number).
            List<String> n = jdbc.queryForList("SELECT full_name FROM users WHERE phone = ? AND role = 'CUSTOMER'", String.class, p.customerPhone());
            if (!n.isEmpty()) p = new Payment(p.reference(), p.customerPhone(), n.get(0), p.amountMinor(), p.currency(), p.productCode(), p.recipient());
        }
        boolean block = false;
        String phoneKey = p.customerPhone() == null ? null : "PHONE:" + NameMatcher.identifier(p.customerPhone());
        for (Rule r : active) {
            try {
                Map<String, Object> detail = check(r, p, phoneKey);
                if (detail == null) continue;
                // Only an ENFORCE rule whose definition two compliance officers approved can block.
                boolean blocks = "ENFORCE".equals(r.mode()) && "BLOCK".equals(r.action()) && r.approved();
                if (detail.containsKey("incomplete")) {
                    boolean blockIncomplete = blocks && "BLOCK".equals(params.get("SCREENING.INCOMPLETE_PAYMENT_ACTION").asText());
                    screeningUnavailable((String) detail.get("incomplete"), p.reference(), blockIncomplete);
                    block |= blockIncomplete;
                    continue;
                }
                String subjectType = "LARGE_TRANSACTION".equals(r.type()) ? "TRANSACTION" : "CUSTOMER";
                String subjectKey = "TRANSACTION".equals(subjectType) ? "TX:" + p.reference() : phoneKey;
                if (subjectKey == null) continue;
                Double score = detail.get("score") instanceof Number n ? n.doubleValue() : null;
                String suffix = "TRANSACTION".equals(subjectType) ? "once"
                        : detail.get("matches") instanceof List<?> ms ? java.time.LocalDate.now() + "|" + matchRefs(ms) : null;
                alerts.record(new AlertStore.Hit(r.code(), r.version(), r.mode(), r.severity(), subjectType, subjectKey, p.customerName(),
                        p.reference(), score, json.writeValueAsString(detail), blocks, suffix));
                block |= blocks;
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("[RISK] rule {} failed for {}: {}", r.code(), p.reference(), e.getMessage());
            }
        }
        if (block) throw new RiskDeclinedException(p.reference());
    }

    private Map<String, Object> check(Rule r, Payment p, String phoneKey) {
        JsonNode q = r.params();
        String ccy = p.currency() == null ? "USD" : p.currency().toUpperCase();
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minusMinutes(q.path("windowMinutes").asLong(60)));
        Map<String, Object> d = new LinkedHashMap<>();
        switch (r.type()) {
            case "VELOCITY_COUNT": {
                if (p.customerPhone() == null) return null;
                int n = count("SELECT COUNT(*) FROM transactions WHERE customer_phone = ? AND created_at >= ?", p.customerPhone(), since) + 1;
                if (n <= q.path("maxCount").asInt(Integer.MAX_VALUE)) return null;
                d.put("count", n); d.put("windowMinutes", q.path("windowMinutes").asLong()); d.put("maxCount", q.path("maxCount").asInt());
                return d;
            }
            case "VELOCITY_AMOUNT": {
                if (p.customerPhone() == null || !q.path("limitMinor").has(ccy)) return null;
                Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents), 0) FROM transactions WHERE customer_phone = ? AND currency = ? AND created_at >= ? "
                        + "AND status NOT IN ('PAYMENT_FAILED','CANCELLED')", Long.class, p.customerPhone(), ccy, since);
                long total = (sum == null ? 0 : sum) + p.amountMinor();
                if (total <= q.path("limitMinor").path(ccy).asLong()) return null;
                d.put("totalMinor", total); d.put("currency", ccy); d.put("limitMinor", q.path("limitMinor").path(ccy).asLong());
                return d;
            }
            case "STRUCTURING": {
                Long tt = threshold(q, ccy);
                if (p.customerPhone() == null || tt == null) return null;
                long t = tt;
                long lower = (long) Math.floor(t * q.path("lowerRatio").asDouble(0.8));
                if (p.amountMinor() < lower || p.amountMinor() >= t) return null;
                int n = count("SELECT COUNT(*) FROM transactions WHERE customer_phone = ? AND currency = ? AND created_at >= ? AND amount_cents >= ? AND amount_cents < ?",
                        p.customerPhone(), ccy, since, lower, t) + 1;
                if (n < q.path("minCount").asInt(3)) return null;
                d.put("count", n); d.put("bandMinor", List.of(lower, t)); d.put("currency", ccy);
                return d;
            }
            case "LARGE_TRANSACTION": {
                Long t = threshold(q, ccy);
                if (t == null || p.amountMinor() < t) return null;
                d.put("amountMinor", p.amountMinor()); d.put("currency", ccy); d.put("thresholdMinor", t);
                return d;
            }
            case "SANCTIONS": {
                if (phoneKey == null) return null;
                List<String> names = new ArrayList<>();
                if (p.customerName() != null) names.add(p.customerName());
                double min = q.path("minScore").isTextual() ? params.number(q.path("minScore").asText()) : q.path("minScore").asDouble(0.88);
                ScreeningService.Result res = screening.screen(phoneKey, "PAYMENT", names, List.of(p.customerPhone()), min);
                if ("INCOMPLETE".equals(res.outcome())) {
                    // Not cleared: the lists are unavailable or outdated. Never treated as a pass.
                    d.put("incomplete", res.incompleteReason());
                    d.put("screeningRunId", res.runId());
                    return d;
                }
                if (res.matches().isEmpty()) return null;
                d.put("score", res.matches().stream().mapToDouble(ScreeningService.Match::score).max().orElse(0));
                d.put("matches", res.matches());
                d.put("screeningRunId", res.runId());
                return d;
            }
            case "TX_LIMIT": {
                if (!q.path("maxMinor").has(ccy) || p.amountMinor() <= q.path("maxMinor").path(ccy).asLong()) return null;
                d.put("amountMinor", p.amountMinor()); d.put("currency", ccy); d.put("maxMinor", q.path("maxMinor").path(ccy).asLong());
                return d;
            }
            case "PERIOD_LIMIT": {
                if (p.customerPhone() == null || !q.path("maxMinor").has(ccy)) return null;
                Timestamp from = Timestamp.valueOf(LocalDateTime.now().minusHours(q.path("periodHours").asLong(24)));
                Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents), 0) FROM transactions WHERE customer_phone = ? AND currency = ? AND created_at >= ? "
                        + "AND status NOT IN ('PAYMENT_FAILED','CANCELLED','PAYMENT_TIMEOUT')", Long.class, p.customerPhone(), ccy, from);
                long total = (sum == null ? 0 : sum) + p.amountMinor();
                if (total <= q.path("maxMinor").path(ccy).asLong()) return null;
                d.put("totalMinor", total); d.put("currency", ccy); d.put("maxMinor", q.path("maxMinor").path(ccy).asLong());
                d.put("periodHours", q.path("periodHours").asLong(24));
                return d;
            }
            case "CDD_THRESHOLD": {
                Long threshold = params.perCurrency(q.path("parameter").asText("CDD.OCCASIONAL_THRESHOLD_MINOR"), ccy);
                if (threshold == null || p.amountMinor() < threshold) return null;
                Integer registered = p.customerPhone() == null ? 0 : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE phone = ? AND role = 'CUSTOMER' AND is_verified = TRUE", Integer.class, p.customerPhone());
                if (registered != null && registered > 0) return null;     // identified customer
                d.put("amountMinor", p.amountMinor()); d.put("currency", ccy); d.put("thresholdMinor", threshold);
                d.put("reason", "occasional customer not identified");
                return d;
            }
            default:
                return null;
        }
    }

    /** A threshold given inline ({"thresholdMinor":{"USD":..}}) or by compliance parameter ({"parameter":"CTR.THRESHOLD_MINOR"}). */
    private Long threshold(JsonNode q, String ccy) {
        if (q.hasNonNull("parameter")) return params.perCurrency(q.path("parameter").asText(), ccy);
        return q.path("thresholdMinor").has(ccy) ? q.path("thresholdMinor").path(ccy).asLong() : null;
    }

    private static String matchRefs(List<?> matches) {
        return matches.stream().map(o -> o instanceof ScreeningService.Match m ? m.listCode() + ":" + m.entryRef() : String.valueOf(o))
                .sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** One HIGH alert per day while screening cannot be completed (lists unavailable/outdated). */
    void screeningUnavailable(String reason, String reference, boolean blocked) {
        try {
            Rule r = rules().stream().filter(x -> "SCREENING_UNAVAILABLE".equals(x.code())).findFirst().orElse(null);
            if (r == null || "OFF".equals(r.mode())) return;
            alerts.record(new AlertStore.Hit(r.code(), r.version(), r.mode(), r.severity(), "SYSTEM", "SYSTEM:SCREENING", "Sanctions screening", reference,
                    null, json.writeValueAsString(Map.of("reason", reason)), blocked, null));
        } catch (Exception e) {
            log.error("[RISK] could not record screening-unavailable alert: {}", e.getMessage());
        }
    }

    /**
     * Merchant screening (business name, owner, registration number) at onboarding, at the KYB
     * decision and after every list update. A match opens a compliance case; INCOMPLETE means the
     * merchant is NOT cleared (KYB approval needs a CLEAR result).
     */
    public ScreeningService.Result screenMerchant(long merchantId, String businessName, String ownerName, String registrationNumber, String context) {
        String key = "MERCHANT:" + merchantId;
        ScreeningService.Result res = screening.screen(key, context, List.of(businessName, ownerName == null ? "" : ownerName),
                registrationNumber == null ? List.of() : List.of(registrationNumber), params.number("SCREENING.MIN_SCORE"));
        try {
            if (!res.matches().isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("score", res.matches().stream().mapToDouble(ScreeningService.Match::score).max().orElse(0));
                d.put("matches", res.matches());
                d.put("screeningRunId", res.runId());
                alerts.record(new AlertStore.Hit("MERCHANT_SCREENING", 1, "ENFORCE", "HIGH", "MERCHANT", key, businessName, null,
                        (Double) d.get("score"), json.writeValueAsString(d), false, "screening|" + matchRefs(res.matches())));
            }
        } catch (Exception e) {
            log.error("[RISK] merchant {} screening alert failed: {}", merchantId, e.getMessage());
        }
        return res;
    }

    public void screenMerchant(long merchantId, String businessName, String ownerName, String registrationNumber) {
        screenMerchant(merchantId, businessName, ownerName, registrationNumber, "ONBOARDING");
    }

    /** Registered customer screening after a list update (name and phone). */
    public ScreeningService.Result screenCustomer(String phone, String fullName, String context) {
        String key = "PHONE:" + NameMatcher.identifier(phone);
        ScreeningService.Result res = screening.screen(key, context, fullName == null ? List.of() : List.of(fullName), List.of(phone),
                params.number("SCREENING.MIN_SCORE"));
        if (!res.matches().isEmpty()) {
            try {
                Rule r = rules().stream().filter(x -> "SANCTIONS".equals(x.type())).findFirst().orElse(null);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("score", res.matches().stream().mapToDouble(ScreeningService.Match::score).max().orElse(0));
                d.put("matches", res.matches());
                d.put("screeningRunId", res.runId());
                d.put("context", context);
                alerts.record(new AlertStore.Hit(r == null ? "SANCTIONS_CUSTOMER" : r.code(), r == null ? 1 : r.version(), r == null ? "OBSERVE" : r.mode(), "HIGH",
                        "CUSTOMER", key, fullName, null, (Double) d.get("score"), json.writeValueAsString(d), false,
                        java.time.LocalDate.now() + "|" + matchRefs(res.matches())));
            } catch (Exception e) {
                log.error("[RISK] customer rescreen alert failed: {}", e.getMessage());
            }
        }
        return res;
    }

    /** After a new list version: every merchant under review or active, and every registered customer. */
    public Map<String, Integer> rescreenAll(String context) {
        int merchants = 0, customers = 0, hits = 0;
        for (Map<String, Object> m : jdbc.queryForList("SELECT m.id, m.business_name, m.registration_number, "
                + "(SELECT u.full_name FROM merchant_users u WHERE u.merchant_id = m.id AND u.role = 'OWNER' LIMIT 1) AS owner "
                + "FROM merchants m WHERE m.status IN ('PENDING','UNDER_REVIEW','UPDATE_REQUIRED','ACTIVE','SUSPENDED')")) {
            ScreeningService.Result r = screenMerchant(((Number) m.get("id")).longValue(), (String) m.get("business_name"), (String) m.get("owner"),
                    (String) m.get("registration_number"), context);
            merchants++;
            if (!r.matches().isEmpty()) hits++;
        }
        for (Map<String, Object> c : jdbc.queryForList("SELECT phone, full_name FROM users WHERE role = 'CUSTOMER' AND is_active = TRUE")) {
            ScreeningService.Result r = screenCustomer((String) c.get("phone"), (String) c.get("full_name"), context);
            customers++;
            if (!r.matches().isEmpty()) hits++;
        }
        log.info("[RISK] Re-screened {} merchants and {} customers after a list update: {} potential matches", merchants, customers, hits);
        return Map.of("merchants", merchants, "customers", customers, "potentialMatches", hits);
    }
}
