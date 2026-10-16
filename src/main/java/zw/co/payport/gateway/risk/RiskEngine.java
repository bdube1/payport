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

    public record Rule(String code, String type, JsonNode params, String mode, String action, String severity, int version) {}

    private final JdbcTemplate jdbc;
    private final AlertStore alerts;
    private final ScreeningService screening;
    private final ObjectMapper json = new ObjectMapper();

    public RiskEngine(JdbcTemplate jdbc, AlertStore alerts, ScreeningService screening) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.screening = screening;
    }

    public List<Rule> rules() {
        return jdbc.query("SELECT * FROM risk_rules ORDER BY code", (rs, i) -> {
            try {
                return new Rule(rs.getString("code"), rs.getString("rule_type"), json.readTree(rs.getString("params")), rs.getString("mode"),
                        rs.getString("action"), rs.getString("severity"), rs.getInt("version"));
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
                boolean blocks = "ENFORCE".equals(r.mode()) && "BLOCK".equals(r.action());
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
                if (p.customerPhone() == null || !q.path("thresholdMinor").has(ccy)) return null;
                long t = q.path("thresholdMinor").path(ccy).asLong();
                long lower = (long) Math.floor(t * q.path("lowerRatio").asDouble(0.8));
                if (p.amountMinor() < lower || p.amountMinor() >= t) return null;
                int n = count("SELECT COUNT(*) FROM transactions WHERE customer_phone = ? AND currency = ? AND created_at >= ? AND amount_cents >= ? AND amount_cents < ?",
                        p.customerPhone(), ccy, since, lower, t) + 1;
                if (n < q.path("minCount").asInt(3)) return null;
                d.put("count", n); d.put("bandMinor", List.of(lower, t)); d.put("currency", ccy);
                return d;
            }
            case "LARGE_TRANSACTION": {
                if (!q.path("thresholdMinor").has(ccy) || p.amountMinor() < q.path("thresholdMinor").path(ccy).asLong()) return null;
                d.put("amountMinor", p.amountMinor()); d.put("currency", ccy); d.put("thresholdMinor", q.path("thresholdMinor").path(ccy).asLong());
                return d;
            }
            case "SANCTIONS": {
                if (phoneKey == null) return null;
                List<String> names = new ArrayList<>();
                if (p.customerName() != null) names.add(p.customerName());
                List<ScreeningService.Match> m = screening.screen(phoneKey, names, List.of(p.customerPhone()), q.path("minScore").asDouble(0.88));
                if (m.isEmpty()) return null;
                d.put("score", m.stream().mapToDouble(ScreeningService.Match::score).max().orElse(0));
                d.put("matches", m);
                return d;
            }
            default:
                return null;
        }
    }

    private static String matchRefs(List<?> matches) {
        return matches.stream().map(o -> o instanceof ScreeningService.Match m ? m.listCode() + ":" + m.entryRef() : String.valueOf(o))
                .sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /**
     * Merchant onboarding screening (business name, owner, registration number). Always on: a hit
     * opens a compliance case, and KYB approval is held until compliance closes it.
     */
    public void screenMerchant(long merchantId, String businessName, String ownerName, String registrationNumber) {
        try {
            String key = "MERCHANT:" + merchantId;
            List<ScreeningService.Match> m = screening.screen(key, List.of(businessName, ownerName == null ? "" : ownerName),
                    registrationNumber == null ? List.of() : List.of(registrationNumber), 0.88);
            if (m.isEmpty()) return;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("score", m.stream().mapToDouble(ScreeningService.Match::score).max().orElse(0));
            d.put("matches", m);
            alerts.record(new AlertStore.Hit("MERCHANT_SCREENING", 1, "ENFORCE", "HIGH", "MERCHANT", key, businessName, null,
                    (Double) d.get("score"), json.writeValueAsString(d), false, "screening|" + matchRefs(m)));
        } catch (Exception e) {
            log.error("[RISK] merchant {} screening failed: {}", merchantId, e.getMessage());
        }
    }
}
