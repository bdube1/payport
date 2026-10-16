package zw.co.payport.gateway.markets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The country and entity model (Phase 7). Each market is one file, markets/&lt;CC&gt;.json:
 * status (LIVE or SANDBOX), the legal entity that keeps its books, currencies (and whether
 * each may take live money), phone numbering, holiday calendar, regulators, the sponsor
 * bank's accounts and which payout and statement formats it uses.
 *
 * Code never names a country or currency; it asks the registry. Adding a sandbox country is
 * a new market file, its reference-data rows and the adapters it needs (see
 * docs/phase7/PHASE7_REPORT.md section 3).
 *
 * Invariants, checked when the application starts (it refuses to start otherwise):
 *  - every market's country, legal entity and currencies exist in the reference data;
 *  - a LIVE market's country is enabled there; a SANDBOX market's is not, and none of its
 *    currencies may be marked live: live money can never move in a sandbox market;
 *  - a currency belongs to exactly one market (settlement batches are per currency);
 *  - every currency has a bank account, and the payout/statement formats are known.
 *
 * Bank details can be overridden per deployment:
 *   payport.markets.&lt;CC&gt;.bank.payout-format, .bank.bic, .bank.name,
 *   payport.markets.&lt;CC&gt;.bank.accounts.&lt;CCY&gt;.number
 */
@Slf4j
@Component
public class MarketRegistry {

    public static final java.util.Set<String> PAYOUT_FORMATS = java.util.Set.of("PAYPORT_CSV_V1", "ISO20022_PAIN_001_001_09");
    public static final java.util.Set<String> STATEMENT_FORMATS = java.util.Set.of("ISO20022_CAMT_053_001_08");

    public record Currency(String code, boolean live, String symbol, String checkoutProductType) {}

    public record BankAccount(String currency, String number, String name, String ledgerOwner) {}

    public record Bank(String name, String bic, String payoutFormat, String statementFormat, String formatStatus, Map<String, BankAccount> accounts) {}

    public record Market(String country, String name, String status, String legalEntity, String timezone, String phoneCountryCode,
                         Pattern nationalPattern, List<Currency> currencies, String holidaysParameter, Map<String, String> regulators,
                         String fiuReportFormat, List<String> requiredScreeningLists, Bank bank) {
        public boolean live() { return "LIVE".equals(status); }

        public Optional<Currency> currency(String code) {
            return currencies.stream().filter(c -> c.code().equalsIgnoreCase(code)).findFirst();
        }
    }

    private final Map<String, Market> markets;
    private final Map<String, Market> byCurrency;
    private final JdbcTemplate jdbc;

    public MarketRegistry(Environment env, JdbcTemplate jdbc) throws IOException {
        this.jdbc = jdbc;
        Map<String, Market> m = new LinkedHashMap<>();
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:markets/*.json");
        ObjectMapper om = new ObjectMapper();
        for (Resource r : files) {
            try (InputStream in = r.getInputStream()) {
                Market market = parse(om.readTree(in), env);
                if (!(market.country() + ".json").equals(r.getFilename())) {
                    throw new IllegalStateException("markets/" + r.getFilename() + " declares country " + market.country());
                }
                if (m.put(market.country(), market) != null) throw new IllegalStateException("market " + market.country() + " defined twice");
            }
        }
        if (m.isEmpty()) throw new IllegalStateException("no markets configured (markets/*.json)");
        Map<String, Market> ccy = new LinkedHashMap<>();
        for (Market market : m.values()) {
            validate(market);
            for (Currency c : market.currencies()) {
                Market other = ccy.put(c.code(), market);
                if (other != null) throw new IllegalStateException("currency " + c.code() + " is in both " + other.country() + " and " + market.country());
            }
        }
        this.markets = Collections.unmodifiableMap(m);
        this.byCurrency = Collections.unmodifiableMap(ccy);
    }

    static Market parse(JsonNode j, Environment env) {
        String cc = text(j, "country").toUpperCase(Locale.ROOT);
        String prefix = "payport.markets." + cc + ".bank.";
        JsonNode b = j.path("bank");
        Map<String, BankAccount> accounts = new LinkedHashMap<>();
        b.path("accounts").fields().forEachRemaining(e -> accounts.put(e.getKey(), new BankAccount(e.getKey(),
                env.getProperty(prefix + "accounts." + e.getKey() + ".number", text(e.getValue(), "number")),
                text(e.getValue(), "name"), text(e.getValue(), "ledgerOwner"))));
        Bank bank = new Bank(env.getProperty(prefix + "name", text(b, "name")), env.getProperty(prefix + "bic", b.path("bic").asText("")),
                env.getProperty(prefix + "payout-format", text(b, "payoutFormat")), env.getProperty(prefix + "statement-format", text(b, "statementFormat")),
                b.path("formatStatus").asText(""), Collections.unmodifiableMap(accounts));
        List<Currency> currencies = new ArrayList<>();
        for (JsonNode c : j.path("currencies")) {
            currencies.add(new Currency(text(c, "code").toUpperCase(Locale.ROOT), c.path("live").asBoolean(false), c.path("symbol").asText(text(c, "code") + " "),
                    text(c, "checkoutProductType")));
        }
        Map<String, String> regulators = new LinkedHashMap<>();
        j.path("regulators").fields().forEachRemaining(e -> regulators.put(e.getKey(), e.getValue().asText()));
        List<String> lists = new ArrayList<>();
        j.path("compliance").path("requiredScreeningLists").forEach(x -> lists.add(x.asText()));
        return new Market(cc, text(j, "name"), text(j, "status"), text(j, "legalEntity"), text(j, "timezone"),
                text(j.path("phone"), "countryCode"), Pattern.compile(text(j.path("phone"), "nationalPattern")), List.copyOf(currencies),
                text(j, "holidaysParameter"), regulators, j.path("compliance").path("fiuReportFormat").asText("NOT_CONFIGURED"), List.copyOf(lists), bank);
    }

    static void validate(Market m) {
        if (!m.country().matches("^[A-Z]{2}$")) throw new IllegalStateException("bad country code " + m.country());
        if (!m.status().equals("LIVE") && !m.status().equals("SANDBOX")) throw new IllegalStateException(m.country() + ": status must be LIVE or SANDBOX");
        if (m.currencies().isEmpty()) throw new IllegalStateException(m.country() + ": no currencies");
        java.time.ZoneId.of(m.timezone());
        for (Currency c : m.currencies()) {
            if (!c.code().matches("^[A-Z]{3}$")) throw new IllegalStateException(m.country() + ": bad currency " + c.code());
            if (c.live() && !m.live()) throw new IllegalStateException(m.country() + " is a SANDBOX market: " + c.code() + " cannot be marked live");
            BankAccount a = m.bank().accounts().get(c.code());
            if (a == null || a.number().isBlank() || a.ledgerOwner().isBlank()) throw new IllegalStateException(m.country() + ": no bank account for " + c.code());
        }
        if (!PAYOUT_FORMATS.contains(m.bank().payoutFormat())) throw new IllegalStateException(m.country() + ": unknown payout format " + m.bank().payoutFormat());
        if (!STATEMENT_FORMATS.contains(m.bank().statementFormat())) throw new IllegalStateException(m.country() + ": unknown statement format " + m.bank().statementFormat());
    }

    /** The reference data must agree with the configuration (the ledger and foreign keys depend on it). */
    @EventListener(ApplicationReadyEvent.class)
    public void checkReferenceData() {
        List<String> problems = referenceDataProblems();
        if (!problems.isEmpty()) throw new IllegalStateException("Market configuration does not match the reference data: " + problems);
        log.info("[MARKETS] {}", summary());
    }

    public List<String> referenceDataProblems() {
        List<String> problems = new ArrayList<>();
        for (Market m : markets.values()) {
            List<Boolean> country = jdbc.queryForList("SELECT enabled FROM ref_countries WHERE code = ?", Boolean.class, m.country());
            if (country.isEmpty()) problems.add(m.country() + ": not in ref_countries");
            else if (country.get(0) != m.live()) problems.add(m.country() + ": ref_countries.enabled must be " + m.live() + " for a " + m.status() + " market");
            List<String> entity = jdbc.queryForList("SELECT country_code FROM ref_legal_entities WHERE code = ? AND status = 'ACTIVE'", String.class, m.legalEntity());
            if (entity.isEmpty()) problems.add(m.country() + ": legal entity " + m.legalEntity() + " missing or inactive");
            else if (!entity.get(0).equals(m.country())) problems.add(m.country() + ": legal entity " + m.legalEntity() + " belongs to " + entity.get(0));
            for (Currency c : m.currencies()) {
                if (jdbc.queryForList("SELECT code FROM ref_currencies WHERE code = ?", String.class, c.code()).isEmpty()) {
                    problems.add(m.country() + ": currency " + c.code() + " not in ref_currencies");
                }
            }
        }
        return problems;
    }

    // ─── lookups ─────────────────────────────────────────────────────────────

    public List<Market> all() {
        return List.copyOf(markets.values());
    }

    public Optional<Market> market(String country) {
        return country == null ? Optional.empty() : Optional.ofNullable(markets.get(country.toUpperCase(Locale.ROOT)));
    }

    public Market require(String country) {
        return market(country).orElseThrow(() -> new IllegalArgumentException("Unknown market " + country + "; configured: " + markets.keySet()));
    }

    public Optional<Market> forCurrency(String currency) {
        return currency == null ? Optional.empty() : Optional.ofNullable(byCurrency.get(currency.toUpperCase(Locale.ROOT)));
    }

    public Market requireForCurrency(String currency) {
        return forCurrency(currency).orElseThrow(() -> new IllegalArgumentException(
                "Unsupported currency " + currency + "; supported: " + String.join(", ", new TreeSet<>(byCurrency.keySet()))));
    }

    /** Any configured currency (test-mode payments may use a sandbox market's currency). */
    public String requireSupportedCurrency(String currency) {
        return requireForCurrency(currency).currency(currency).orElseThrow().code();
    }

    /** True only for a currency of a LIVE market that is itself marked live. */
    public boolean liveCurrency(String currency) {
        return forCurrency(currency).map(m -> m.live() && m.currency(currency).map(Currency::live).orElse(false)).orElse(false);
    }

    public String symbol(String currency) {
        return forCurrency(currency).flatMap(m -> m.currency(currency)).map(Currency::symbol).orElse(currency + " ");
    }

    public String checkoutProductType(String currency) {
        return requireForCurrency(currency).currency(currency).orElseThrow().checkoutProductType();
    }

    public BankAccount bankAccount(String currency) {
        return requireForCurrency(currency).bank().accounts().get(currency.toUpperCase(Locale.ROOT));
    }

    /** E.164 without '+', or empty when the number is not a valid mobile number in any configured market. */
    public Optional<String> normaliseMobile(String raw, String country) {
        if (raw == null) return Optional.empty();
        String digits = raw.replaceAll("[^0-9]", "");
        Market m = require(country);
        String national = digits.startsWith(m.phoneCountryCode()) ? digits.substring(m.phoneCountryCode().length())
                : digits.startsWith("0") ? digits.substring(1) : digits;
        return m.nationalPattern().matcher(national).matches() ? Optional.of(m.phoneCountryCode() + national) : Optional.empty();
    }

    public List<Map<String, Object>> summary() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Market m : markets.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("country", m.country());
            x.put("name", m.name());
            x.put("status", m.status());
            x.put("legalEntity", m.legalEntity());
            x.put("timezone", m.timezone());
            x.put("currencies", m.currencies());
            x.put("phoneCountryCode", m.phoneCountryCode());
            x.put("regulators", m.regulators());
            x.put("fiuReportFormat", m.fiuReportFormat());
            x.put("requiredScreeningLists", m.requiredScreeningLists());
            x.put("payoutFormat", m.bank().payoutFormat());
            x.put("statementFormat", m.bank().statementFormat());
            x.put("bankFormatStatus", m.bank().formatStatus());
            x.put("bankAccounts", m.bank().accounts().values().stream().map(a -> Map.of("currency", a.currency(), "name", a.name(),
                    "number", mask(a.number()), "ledgerOwner", a.ledgerOwner())).toList());
            out.add(x);
        }
        return out;
    }

    private static String mask(String n) {
        return n.length() <= 4 ? "****" : "****" + n.substring(n.length() - 4);
    }

    private static String text(JsonNode j, String field) {
        JsonNode v = j.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) throw new IllegalStateException("market config: '" + field + "' is required");
        return v.asText();
    }
}
