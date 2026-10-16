package zw.co.payport.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The integration register: every provider PayPort talks to, what it can do (capability
 * declarations), which environment it is configured against right now, and how far it has been
 * verified, with the evidence (provider_verifications). Source: classpath integrations/register.json,
 * kept in step with the adaptors by IntegrationRegisterTest.
 */
@Component
public class IntegrationRegister {

    public enum Verification { SPEC_ONLY, SANDBOX_VERIFIED, PRODUCTION_VERIFIED }

    public record Provider(String code, String name, String kind, List<String> currencies, List<String> operations,
                           String callbackAuth, String endpointProperty, Verification verification, String notes) {
        public boolean supportsCurrency(String c) { return c != null && currencies.contains(c.toUpperCase(Locale.ROOT)); }
    }

    private static final Set<String> FLOWS = Set.of("submit", "callback", "status_query", "refund", "otp_confirm", "widget",
            "deliver", "lookup", "resend_lookup");

    private final Map<String, Provider> providers;
    private final Environment env;
    private final JdbcTemplate jdbc;

    public IntegrationRegister(Environment env, JdbcTemplate jdbc) {
        this.env = env;
        this.jdbc = jdbc;
        this.providers = withCurrencyOverrides(load(), env);
    }

    /**
     * A provider's currencies can be widened or narrowed per deployment (a new market switched on from
     * the portal, e.g. MTN MoMo in another country) with payport.integrations.CODE.currencies=ZMW,UGX.
     */
    static Map<String, Provider> withCurrencyOverrides(Map<String, Provider> loaded, Environment env) {
        if (env == null) return loaded;
        Map<String, Provider> m = new LinkedHashMap<>();
        for (Provider p : loaded.values()) {
            String o = env.getProperty("payport.integrations." + p.code() + ".currencies", "");
            if (o.isBlank()) { m.put(p.code(), p); continue; }
            List<String> ccy = java.util.Arrays.stream(o.split(",")).map(String::trim).filter(c -> !c.isEmpty()).map(c -> c.toUpperCase(Locale.ROOT)).toList();
            m.put(p.code(), new Provider(p.code(), p.name(), p.kind(), ccy, p.operations(), p.callbackAuth(), p.endpointProperty(), p.verification(),
                    p.notes() + " [currencies overridden by configuration]"));
        }
        return m;
    }

    static Map<String, Provider> load() {
        try (InputStream in = new ClassPathResource("integrations/register.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, Provider> m = new LinkedHashMap<>();
            for (JsonNode p : root.path("providers")) {
                List<String> ccy = new ArrayList<>();
                p.path("currencies").forEach(c -> ccy.add(c.asText().toUpperCase(Locale.ROOT)));
                List<String> ops = new ArrayList<>();
                p.path("operations").forEach(o -> {
                    if (!FLOWS.contains(o.asText())) throw new IllegalStateException("unknown operation " + o.asText() + " for " + p.path("code").asText());
                    ops.add(o.asText());
                });
                Provider pr = new Provider(p.path("code").asText().toUpperCase(Locale.ROOT), p.path("name").asText(), p.path("kind").asText(),
                        List.copyOf(ccy), List.copyOf(ops), p.path("callbackAuth").asText(), p.path("endpointProperty").asText(),
                        Verification.valueOf(p.path("verification").asText()), p.path("notes").asText());
                if (m.put(pr.code(), pr) != null) throw new IllegalStateException("duplicate register entry " + pr.code());
            }
            return m;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("integration register unreadable", e);
        }
    }

    public Map<String, Provider> all() { return providers; }

    public Provider get(String code) { return code == null ? null : providers.get(code.toUpperCase(Locale.ROOT)); }

    /** TEST, UAT or PRODUCTION, judged from the configured endpoint (no network call). */
    public String configuredEnvironment(Provider p) {
        if (p.endpointProperty() == null || p.endpointProperty().isBlank()) return "INTERNAL";
        String url = env.getProperty(p.endpointProperty(), "");
        if (url.isBlank()) return "NOT_CONFIGURED";
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("sandbox") || u.contains("test") || u.contains("/uat") || u.contains("uat.")) return "TEST";
        if (u.startsWith("http://")) return "INSECURE_HTTP";
        return "PRODUCTION";
    }

    /** Endpoint host only (no credentials, path or query). */
    public String endpointHost(Provider p) {
        if (p.endpointProperty() == null || p.endpointProperty().isBlank()) return null;
        try {
            return java.net.URI.create(env.getProperty(p.endpointProperty(), "")).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    public void recordVerification(String code, String environment, String flow, boolean pass, String evidence, String by) {
        Provider p = get(code);
        if (p == null) throw new IllegalArgumentException("unknown provider " + code);
        if (!FLOWS.contains(flow)) throw new IllegalArgumentException("unknown flow " + flow + " (one of " + FLOWS + ")");
        if (!p.operations().contains(flow)) throw new IllegalArgumentException(p.code() + " does not declare " + flow);
        jdbc.update("INSERT INTO provider_verifications (provider_code, environment, flow, result, evidence, recorded_by, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                p.code(), environment.toUpperCase(Locale.ROOT), flow, pass ? "PASS" : "FAIL", evidence, by, Timestamp.valueOf(LocalDateTime.now()));
    }

    public List<Map<String, Object>> verifications(String code) {
        return jdbc.queryForList("SELECT environment, flow, result, evidence, recorded_by, recorded_at FROM provider_verifications "
                + "WHERE provider_code = ? ORDER BY id DESC LIMIT 100", code.toUpperCase(Locale.ROOT));
    }
}
