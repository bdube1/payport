package zw.co.payport.gateway.merchant;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.risk.ComplianceAudit;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Payment methods a merchant accepts (owner, 31 Oct 2026). Payport switches a method on for the platform; the merchant
 * then chooses which of those it takes. Hosted checkout, payment links and the store show only those, and a payment
 * with any other method is refused before anything is sent to a provider.
 *
 * Store payments stay in sandbox (test mode) until Payport switches them to live ("commerce_livemode"), which needs the
 * owner's approval; the readiness view shows which providers have their credentials in place, never the credentials.
 */
@Service
public class MerchantPaymentMethods {

    /** Methods with a provider adaptor behind them, in the order customers see them. */
    public static final List<String> CHECKOUT = List.of("ECOCASH", "ONEMONEY", "OMARI", "INNBUCKS", "ZIMSWITCH");

    private static final Map<String, List<String>> CREDENTIALS = Map.of(
            "ECOCASH", List.of("payport.ecocash.merchant-code", "payport.ecocash.username", "payport.ecocash.password"),
            "ONEMONEY", List.of("payport.onemoney.merchant-no", "payport.onemoney.merchant-private-key"),
            "OMARI", List.of("payport.omari.api-key"),
            "INNBUCKS", List.of("payport.innbucks.base-url", "payport.innbucks.api-key"),
            "ZIMSWITCH", List.of("payport.zimswitch.entity-id", "payport.zimswitch.bearer-token"));

    private final JdbcTemplate jdbc;
    private final ComplianceAudit audit;
    private final Environment env;

    public MerchantPaymentMethods(JdbcTemplate jdbc, ComplianceAudit audit, Environment env) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.env = env;
    }

    /** Every method Payport has switched on, with whether this merchant accepts it. */
    public List<Map<String, Object>> forMerchant(long merchantId) {
        Map<String, Boolean> mine = new LinkedHashMap<>();
        jdbc.query("SELECT method_code, enabled FROM merchant_payment_methods WHERE merchant_id = ?",
                rs -> { mine.put(rs.getString(1), rs.getBoolean(2)); }, merchantId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : platform()) {
            String code = (String) r.get("code");
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("code", code);
            v.put("name", label(code, (String) r.get("name")));
            v.put("type", "ZIMSWITCH".equals(code) ? "CARD" : "MOBILE_MONEY");
            v.put("enabled", mine.getOrDefault(code, true));
            out.add(v);
        }
        return out;
    }

    /** The codes this merchant accepts now. */
    public List<String> accepted(long merchantId) {
        return forMerchant(merchantId).stream().filter(m -> Boolean.TRUE.equals(m.get("enabled"))).map(m -> (String) m.get("code")).toList();
    }

    @Transactional
    public List<Map<String, Object>> set(long merchantId, Map<String, ?> changes, String by) {
        if (changes == null || changes.isEmpty()) throw new IllegalArgumentException("Choose the payment methods you accept");
        List<String> platform = platform().stream().map(r -> (String) r.get("code")).toList();
        Map<String, Boolean> want = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : changes.entrySet()) {
            String code = e.getKey() == null ? "" : e.getKey().toUpperCase(Locale.ROOT);
            if (!platform.contains(code)) throw new IllegalArgumentException("Unknown payment method " + e.getKey());
            want.put(code, Boolean.TRUE.equals(e.getValue()) || "true".equals(String.valueOf(e.getValue())));
        }
        long on = forMerchant(merchantId).stream().filter(m -> want.getOrDefault((String) m.get("code"), Boolean.TRUE.equals(m.get("enabled")))).count();
        if (on == 0) throw new IllegalArgumentException("Keep at least one payment method on");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (Map.Entry<String, Boolean> e : want.entrySet()) {
            jdbc.update("INSERT INTO merchant_payment_methods (merchant_id, method_code, enabled, updated_by, updated_at) VALUES (?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), updated_by = VALUES(updated_by), updated_at = VALUES(updated_at)", merchantId, e.getKey(), e.getValue(), by, now);
        }
        audit.record(by, "MERCHANT_PAYMENT_METHODS", "merchant:" + merchantId, want.toString());
        return forMerchant(merchantId);
    }

    /** Refuses a method this merchant does not take (or Payport has switched off), before anything reaches a provider. */
    public void require(long merchantId, String code) {
        String c = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (!CHECKOUT.contains(c)) return;   // the Payport wallet and other rails have their own switches
        if (!accepted(merchantId).contains(c)) {
            throw new IllegalArgumentException("This business does not accept " + label(c, c) + ". Choose another way to pay. No money was taken.");
        }
    }

    /** A method code as sent by a store, if this merchant accepts it; otherwise none. */
    public String preferred(long merchantId, String code) {
        String c = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
        return c != null && accepted(merchantId).contains(c) ? c : null;
    }

    // ─── live readiness (prepared; switched on only with the owner's approval) ─

    public boolean commerceLive() {
        return jdbc.queryForList("SELECT setting_value FROM platform_settings WHERE setting_key = 'commerce_livemode'", String.class)
                .stream().findFirst().map("true"::equalsIgnoreCase).orElse(false);
    }

    public Map<String, Object> readiness() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> active = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT code, name, is_active FROM payment_methods WHERE code IN ('ECOCASH','ONEMONEY','OMARI','INNBUCKS','ZIMSWITCH')"))
            active.put((String) r.get("code"), r);
        for (String code : CHECKOUT) {
            Map<String, Object> r = active.containsKey(code) ? castMap(active.get(code)) : Map.of();
            List<String> missing = new ArrayList<>();
            for (String k : CREDENTIALS.getOrDefault(code, List.of())) {
                String v = env.getProperty(k);
                if (v == null || v.isBlank()) missing.add(k.substring(k.lastIndexOf('.') + 1));
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("code", code);
            v.put("name", label(code, r.get("name") == null ? code : (String) r.get("name")));
            v.put("switchedOn", Boolean.TRUE.equals(r.get("is_active")) || Integer.valueOf(1).equals(r.get("is_active")));
            v.put("credentialsInPlace", missing.isEmpty());
            v.put("missing", missing);
            rows.add(v);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("environment", env.getProperty("payport.environment", "production"));
        out.put("storePaymentsLive", commerceLive());
        out.put("methods", rows);
        out.put("readyForLive", rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("switchedOn")) && Boolean.TRUE.equals(r.get("credentialsInPlace"))));
        return out;
    }

    /** Store payments to live or back to sandbox. Live needs a reason (the owner's approval) and a provider ready for it. */
    @Transactional
    public Map<String, Object> setCommerceLive(boolean live, String reason, String by) {
        if (live) {
            if (reason == null || reason.trim().length() < 5) throw new IllegalArgumentException("Give the approval for switching store payments to live");
            if (!Boolean.TRUE.equals(readiness().get("readyForLive"))) throw new IllegalStateException("No payment method has its provider credentials in place yet");
        }
        jdbc.update("INSERT INTO platform_settings (setting_key, setting_value, updated_at) VALUES ('commerce_livemode', ?, NOW()) "
                + "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW()", live ? "true" : "false");
        audit.record(by, live ? "STORE_PAYMENTS_LIVE" : "STORE_PAYMENTS_SANDBOX", "platform_settings:commerce_livemode", reason == null ? "" : reason.trim());
        return readiness();
    }

    private List<Map<String, Object>> platform() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT code, name FROM payment_methods WHERE is_active = TRUE AND code IN ('ECOCASH','ONEMONEY','OMARI','INNBUCKS','ZIMSWITCH')");
        rows.sort(java.util.Comparator.comparingInt(r -> CHECKOUT.indexOf((String) r.get("code"))));
        return rows;
    }

    static String label(String code, String name) {
        return "ZIMSWITCH".equals(code) ? "Card (Visa, Mastercard, ZimSwitch)" : name;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }
}
