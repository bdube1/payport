package zw.co.payport.gateway.markets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 * The country and entity model (Phase 7), operated from the portal. Each market is a row in
 * `markets` with its currencies in `market_currencies` (V50): status (LIVE, SANDBOX or SUSPENDED),
 * the legal entity that keeps its books, currencies (enabled for payments at all, and whether each
 * may take live money), phone numbering, holiday calendar, regulators, the sponsor bank's accounts
 * and the payout and statement formats (plus an optional bank usage guideline for statements).
 *
 * Changes go through MarketAdminService (two-person approval for anything that enables money;
 * switching off is immediate). Code never names a country or currency; it asks this registry.
 *
 * The registry reloads at most every 30 seconds, so every instance follows a change within half a
 * minute; MarketAdminService reloads immediately on the instance that applied it.
 *
 * Invariants, checked on every load (a market that breaks one is withdrawn: it takes no payments,
 * and the problem is reported by problems() and on the ops console):
 *  - a SANDBOX market's currencies are never live; live implies enabled;
 *  - a LIVE market's country is enabled in the reference data, a SANDBOX market's is not;
 *  - the legal entity exists, is active and belongs to the market's country;
 *  - every currency has a bank account and ledger owner; the payout and statement formats are known.
 */
@Slf4j
@Component
public class MarketRegistry {

    public static final java.util.Set<String> PAYOUT_FORMATS = java.util.Set.of("PAYPORT_CSV_V1", "ISO20022_PAIN_001_001_09");
    public static final java.util.Set<String> STATEMENT_FORMATS = java.util.Set.of("ISO20022_CAMT_053_001_08");
    public static final java.util.Set<String> STATUSES = java.util.Set.of("LIVE", "SANDBOX", "SUSPENDED");
    private static final long MAX_AGE_MS = 30_000;

    public record Currency(String code, boolean enabled, boolean live, String symbol, String checkoutProductType, String isoNumeric) {}

    public record BankAccount(String currency, String number, String name, String ledgerOwner) {}

    public record Bank(String name, String bic, String payoutFormat, String statementFormat, String statementGuideline,
                       String formatStatus, Map<String, BankAccount> accounts) {}

    public record Market(String country, String name, String status, String statusNote, String legalEntity, String timezone,
                         String phoneCountryCode, Pattern nationalPattern, List<Currency> currencies, String holidaysParameter,
                         Map<String, String> regulators, String fiuReportFormat, List<String> requiredScreeningLists, Bank bank, int version) {
        public boolean live() { return "LIVE".equals(status); }

        public Optional<Currency> currency(String code) {
            return currencies.stream().filter(c -> c.code().equalsIgnoreCase(code)).findFirst();
        }
    }

    private record Snapshot(Map<String, Market> markets, Map<String, Market> byCurrency, Map<String, List<String>> problems, long loadedAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();
    private volatile Snapshot snap;

    public MarketRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─── loading ─────────────────────────────────────────────────────────────

    private Snapshot snapshot() {
        Snapshot s = snap;
        if (s == null || System.currentTimeMillis() - s.loadedAt() > MAX_AGE_MS) {
            reload();
            s = snap;
        }
        return s;
    }

    /** Re-reads every market now. */
    public synchronized void reload() {
        snap = load();
    }

    /** Problems the current database state would cause for one market, without changing what is in use. */
    public List<String> loadProblems(String country) {
        return load().problems().getOrDefault(country, List.of());
    }

    private Snapshot load() {
        Map<String, List<Currency>> ccys = new LinkedHashMap<>();
        Map<String, Map<String, BankAccount>> accounts = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM market_currencies ORDER BY country, currency", rs -> {
            String cc = rs.getString("country"), code = rs.getString("currency");
            ccys.computeIfAbsent(cc, k -> new ArrayList<>()).add(new Currency(code, rs.getBoolean("enabled"), rs.getBoolean("live"),
                    rs.getString("symbol"), rs.getString("checkout_product_type"), rs.getString("iso_numeric")));
            accounts.computeIfAbsent(cc, k -> new LinkedHashMap<>()).put(code, new BankAccount(code, rs.getString("bank_account_number"),
                    rs.getString("bank_account_name"), rs.getString("ledger_owner")));
        });
        Map<String, Market> markets = new LinkedHashMap<>();
        Map<String, List<String>> problems = new LinkedHashMap<>();
        List<Market> loaded = new ArrayList<>();
        jdbc.query("SELECT * FROM markets ORDER BY country", rs -> {
            String cc = rs.getString("country");
            try {
                Bank bank = new Bank(rs.getString("bank_name"), rs.getString("bank_bic"), rs.getString("payout_format"), rs.getString("statement_format"),
                        rs.getString("statement_guideline"), rs.getString("bank_format_status"),
                        Collections.unmodifiableMap(accounts.getOrDefault(cc, Map.of())));
                Map<String, String> regulators = new LinkedHashMap<>();
                om.readTree(rs.getString("regulators_json")).fields().forEachRemaining(e -> regulators.put(e.getKey(), e.getValue().asText()));
                List<String> lists = new ArrayList<>();
                om.readTree(rs.getString("required_lists_json")).forEach(x -> lists.add(x.asText()));
                Market m = new Market(cc, rs.getString("name"), rs.getString("status"), rs.getString("status_note"), rs.getString("legal_entity"),
                        rs.getString("timezone"), rs.getString("phone_country_code"), Pattern.compile(rs.getString("national_pattern")),
                        List.copyOf(ccys.getOrDefault(cc, List.of())), rs.getString("holidays_parameter"), regulators,
                        rs.getString("fiu_report_format"), List.copyOf(lists), bank, rs.getInt("version"));
                loaded.add(m);
            } catch (Exception e) {
                problems.put(cc, List.of("unreadable settings: " + e.getMessage()));
            }
        });
        for (Market m : loaded) {
            List<String> p = new ArrayList<>(validate(m));
            p.addAll(referenceDataProblems(m));
            if (p.isEmpty()) markets.put(m.country(), m);
            else problems.put(m.country(), p);
        }
        Map<String, Market> byCurrency = new LinkedHashMap<>();
        for (Market m : markets.values()) for (Currency c : m.currencies()) byCurrency.put(c.code(), m);
        return new Snapshot(Collections.unmodifiableMap(markets), Collections.unmodifiableMap(byCurrency), Collections.unmodifiableMap(problems),
                System.currentTimeMillis());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logOnStart() {
        reload();
        Snapshot s = snap;
        log.info("[MARKETS] {}", s.markets().keySet());
        if (!s.problems().isEmpty()) log.error("[MARKETS] withdrawn (they take no payments until fixed): {}", s.problems());
    }

    /** Configuration rules that do not need the database. Empty = valid. */
    public static List<String> validate(Market m) {
        List<String> p = new ArrayList<>();
        if (m.country() == null || !m.country().matches("^[A-Z]{2}$")) p.add("bad country code " + m.country());
        if (!STATUSES.contains(m.status())) p.add("status must be LIVE, SANDBOX or SUSPENDED");
        if (m.currencies().isEmpty()) p.add("no currencies");
        try { java.time.ZoneId.of(m.timezone()); } catch (Exception e) { p.add("unknown timezone " + m.timezone()); }
        for (Currency c : m.currencies()) {
            if (!c.code().matches("^[A-Z]{3}$")) p.add("bad currency " + c.code());
            if (c.isoNumeric() != null && !c.isoNumeric().matches("^[0-9]{3}$")) p.add("bad ISO numeric code for " + c.code());
            if (c.live() && !c.enabled()) p.add(c.code() + " cannot be live while disabled");
            if (c.live() && "SANDBOX".equals(m.status())) p.add(m.country() + " is a SANDBOX market: " + c.code() + " cannot be live");
            BankAccount a = m.bank().accounts().get(c.code());
            if (a == null || a.number() == null || a.number().isBlank() || a.ledgerOwner() == null || a.ledgerOwner().isBlank()
                    || a.ledgerOwner().contains(":")) p.add("no bank account or ledger owner for " + c.code());
        }
        if (!PAYOUT_FORMATS.contains(m.bank().payoutFormat())) p.add("unknown payout format " + m.bank().payoutFormat());
        if (!STATEMENT_FORMATS.contains(m.bank().statementFormat())) p.add("unknown statement format " + m.bank().statementFormat());
        if (m.bank().bic() != null && !m.bank().bic().isBlank() && !m.bank().bic().matches("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?")) {
            p.add("BIC " + m.bank().bic() + " is not a valid BIC");
        }
        return p;
    }

    private List<String> referenceDataProblems(Market m) {
        List<String> p = new ArrayList<>();
        List<Boolean> country = jdbc.queryForList("SELECT enabled FROM ref_countries WHERE code = ?", Boolean.class, m.country());
        if (country.isEmpty()) p.add("not in ref_countries");
        else if ("LIVE".equals(m.status()) && !country.get(0)) p.add("LIVE but ref_countries.enabled is false");
        else if ("SANDBOX".equals(m.status()) && country.get(0)) p.add("SANDBOX but ref_countries.enabled is true");
        List<String> entity = jdbc.queryForList("SELECT country_code FROM ref_legal_entities WHERE code = ? AND status = 'ACTIVE'", String.class, m.legalEntity());
        if (entity.isEmpty()) p.add("legal entity " + m.legalEntity() + " missing or inactive");
        else if (!entity.get(0).equals(m.country())) p.add("legal entity " + m.legalEntity() + " belongs to " + entity.get(0));
        for (Currency c : m.currencies()) {
            List<Boolean> en = jdbc.queryForList("SELECT enabled FROM ref_currencies WHERE code = ?", Boolean.class, c.code());
            if (en.isEmpty()) p.add("currency " + c.code() + " not in ref_currencies");
            else if (c.live() && "LIVE".equals(m.status()) && !en.get(0)) p.add("currency " + c.code() + " is live but not enabled in the ledger reference data");
        }
        return p;
    }

    /** Markets withdrawn because their settings break an invariant (country -> problems). */
    public Map<String, List<String>> problems() {
        return snapshot().problems();
    }

    // ─── lookups ─────────────────────────────────────────────────────────────

    public List<Market> all() {
        return List.copyOf(snapshot().markets().values());
    }

    public Optional<Market> market(String country) {
        return country == null ? Optional.empty() : Optional.ofNullable(snapshot().markets().get(country.toUpperCase(Locale.ROOT)));
    }

    public Market require(String country) {
        return market(country).orElseThrow(() -> new IllegalArgumentException("Unknown market " + country + "; configured: " + snapshot().markets().keySet()));
    }

    /** The market a currency belongs to (enabled or not: existing balances and payouts still need it). */
    public Optional<Market> forCurrency(String currency) {
        return currency == null ? Optional.empty() : Optional.ofNullable(snapshot().byCurrency().get(currency.toUpperCase(Locale.ROOT)));
    }

    public Market requireForCurrency(String currency) {
        return forCurrency(currency).orElseThrow(() -> new IllegalArgumentException(
                "Unsupported currency " + currency + "; supported: " + String.join(", ", new TreeSet<>(enabledCurrencies()))));
    }

    public List<String> enabledCurrencies() {
        List<String> out = new ArrayList<>();
        for (Market m : snapshot().markets().values()) {
            if ("SUSPENDED".equals(m.status())) continue;
            for (Currency c : m.currencies()) if (c.enabled()) out.add(c.code());
        }
        return out;
    }

    /** A currency switched on for payments (test or live) in a market that is not suspended. */
    public boolean supportedCurrency(String currency) {
        return currency != null && enabledCurrencies().contains(currency.toUpperCase(Locale.ROOT));
    }

    /** Any enabled currency (test-mode payments may use a sandbox market's currency). */
    public String requireSupportedCurrency(String currency) {
        if (!supportedCurrency(currency)) {
            throw new IllegalArgumentException("Unsupported currency " + currency + "; supported: " + String.join(", ", new TreeSet<>(enabledCurrencies())));
        }
        return currency.toUpperCase(Locale.ROOT);
    }

    /** True only for an enabled, live currency of a LIVE market. */
    public boolean liveCurrency(String currency) {
        return forCurrency(currency).map(m -> m.live() && m.currency(currency).map(c -> c.enabled() && c.live()).orElse(false)).orElse(false);
    }

    public String symbol(String currency) {
        return forCurrency(currency).flatMap(m -> m.currency(currency)).map(Currency::symbol).orElse(currency + " ");
    }

    public String checkoutProductType(String currency) {
        return requireForCurrency(currency).currency(currency).orElseThrow().checkoutProductType();
    }

    /** ISO 4217 numeric code (e.g. for EMV QR), if configured. */
    public Optional<String> isoNumeric(String currency) {
        return forCurrency(currency).flatMap(m -> m.currency(currency)).map(Currency::isoNumeric).filter(x -> x != null && !x.isBlank());
    }

    /** The market whose phone country code prefixes this international number (digits only). */
    public Optional<Market> forMsisdn(String msisdn) {
        if (msisdn == null) return Optional.empty();
        String d = msisdn.replaceAll("[^0-9]", "");
        return all().stream().filter(m -> d.startsWith(m.phoneCountryCode())).findFirst();
    }

    public BankAccount bankAccount(String currency) {
        return requireForCurrency(currency).bank().accounts().get(currency.toUpperCase(Locale.ROOT));
    }

    /** E.164 without '+', or empty when the number is not a valid mobile number in that market. */
    public Optional<String> normaliseMobile(String raw, String country) {
        if (raw == null) return Optional.empty();
        String digits = raw.replaceAll("[^0-9]", "");
        Market m = require(country);
        String national = digits.startsWith(m.phoneCountryCode()) ? digits.substring(m.phoneCountryCode().length())
                : digits.startsWith("0") ? digits.substring(1) : digits;
        return m.nationalPattern().matcher(national).matches() ? Optional.of(m.phoneCountryCode() + national) : Optional.empty();
    }

    /**
     * True when the E.164 number is a valid mobile number in a market that is not suspended. Used for
     * customer phone numbers, so a market switched on from the portal accepts its own customers.
     */
    public boolean validMobileInAnyMarket(String e164) {
        if (e164 == null) return false;
        String digits = e164.replaceAll("[^0-9]", "");
        for (Market m : snapshot().markets().values()) {
            if ("SUSPENDED".equals(m.status()) || !digits.startsWith(m.phoneCountryCode())) continue;
            if (m.nationalPattern().matcher(digits.substring(m.phoneCountryCode().length())).matches()) return true;
        }
        return false;
    }

    public List<Map<String, Object>> summary() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Market m : snapshot().markets().values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("country", m.country());
            x.put("name", m.name());
            x.put("status", m.status());
            x.put("statusNote", m.statusNote());
            x.put("legalEntity", m.legalEntity());
            x.put("timezone", m.timezone());
            x.put("currencies", m.currencies());
            x.put("phoneCountryCode", m.phoneCountryCode());
            x.put("nationalPattern", m.nationalPattern().pattern());
            x.put("holidaysParameter", m.holidaysParameter());
            x.put("regulators", m.regulators());
            x.put("fiuReportFormat", m.fiuReportFormat());
            x.put("requiredScreeningLists", m.requiredScreeningLists());
            x.put("bankName", m.bank().name());
            x.put("bankBic", m.bank().bic());
            x.put("payoutFormat", m.bank().payoutFormat());
            x.put("statementFormat", m.bank().statementFormat());
            x.put("statementGuideline", m.bank().statementGuideline());
            x.put("bankFormatStatus", m.bank().formatStatus());
            x.put("bankAccounts", m.bank().accounts().values().stream().map(a -> Map.of("currency", a.currency(), "name", a.name(),
                    "number", mask(a.number()), "ledgerOwner", a.ledgerOwner())).toList());
            x.put("version", m.version());
            out.add(x);
        }
        return out;
    }

    static String mask(String n) {
        return n == null || n.length() <= 4 ? "****" : "****" + n.substring(n.length() - 4);
    }

    static JsonNode tree(ObjectMapper om, String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage()); }
    }
}
