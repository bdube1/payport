package zw.co.payport.gateway.markets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.payport.gateway.platform.ApprovalStore;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.settlement.PayoutFormats;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Operating markets and currencies from the portal, without code changes.
 *
 *  - Switching OFF is immediate (one person, reason required, audited): suspend a market, disable a
 *    currency, or stop live money in a currency. Stopping money must never wait for a second person.
 *  - Everything else is a request that a second person approves. A change that ENABLES money
 *    (a market going LIVE, a currency becoming live, adding a currency) must be approved by a
 *    COMPLIANCE officer; other settings by another ADMIN. Nobody approves their own request.
 *  - Before a market goes LIVE its readiness is checked (legal entity, bank accounts that are not
 *    placeholders, payout adapter, required screening lists loaded). Warnings are shown to the approver.
 *  - Reference data the ledger relies on (country enabled, currency enabled, legal entity name) is
 *    changed through the definer procedures of V50, in the same transaction as the market change.
 *  - Every request, decision and immediate switch is written to the hash-chained audit log.
 */
@Slf4j
@Service
public class MarketAdminService {

    public static final String ACTION_CHANGE = "MARKET_CHANGE";
    public static final String ACTION_CREATE = "MARKET_CREATE";
    static final Set<String> MARKET_FIELDS = Set.of("name", "status", "statusNote", "legalEntityName", "timezone", "phoneCountryCode",
            "nationalPattern", "holidaysParameter", "fiuReportFormat", "regulators", "requiredScreeningLists", "bankName", "bankBic",
            "payoutFormat", "statementFormat", "statementGuideline", "bankFormatStatus");
    static final Set<String> CURRENCY_FIELDS = Set.of("currency", "enabled", "live", "symbol", "checkoutProductType", "bankAccountNumber",
            "bankAccountName", "ledgerOwner", "name", "minorUnits", "isoNumeric");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i).*(TO-BE-CONFIRMED|SANDBOX|PLACEHOLDER|TBC|XXXX).*");

    private final JdbcTemplate jdbc;
    private final MarketRegistry registry;
    private final ComplianceAudit audit;
    private final PayoutFormats payoutFormats;
    private final ObjectMapper json = new ObjectMapper();

    public MarketAdminService(JdbcTemplate jdbc, MarketRegistry registry, ComplianceAudit audit, PayoutFormats payoutFormats) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.audit = audit;
        this.payoutFormats = payoutFormats;
    }

    // ─── view ────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> view() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<String>> problems = registry.problems();
        for (Map<String, Object> row : jdbc.queryForList("SELECT m.*, e.legal_name FROM markets m JOIN ref_legal_entities e ON e.code = m.legal_entity ORDER BY m.country")) {
            String cc = (String) row.get("country");
            Map<String, Object> m = new LinkedHashMap<>(row);
            List<Map<String, Object>> ccys = jdbc.queryForList("SELECT * FROM market_currencies WHERE country = ? ORDER BY currency", cc);
            for (Map<String, Object> c : ccys) c.put("bank_account_number", MarketRegistry.mask((String) c.get("bank_account_number")));
            m.put("currencies", ccys);
            m.put("readiness", readiness(row, jdbc.queryForList("SELECT * FROM market_currencies WHERE country = ?", cc)));
            m.put("problems", problems.getOrDefault(cc, List.of()));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> requests() {
        return jdbc.queryForList("SELECT id, approval_ref, action_type, subject_ref, payload, reason, required_role, status, requested_by, requested_at, "
                + "decided_by, expires_at FROM platform_approvals WHERE action_type IN ('" + ACTION_CHANGE + "','" + ACTION_CREATE + "') ORDER BY id DESC LIMIT 100");
    }

    /** What would stop the market going LIVE (blockers), and what the approver should know (warnings). */
    Map<String, Object> readiness(Map<String, Object> market, List<Map<String, Object>> currencies) {
        List<String> blockers = new ArrayList<>(), warnings = new ArrayList<>();
        Integer entity = jdbc.queryForObject("SELECT COUNT(*) FROM ref_legal_entities WHERE code = ? AND status = 'ACTIVE' AND country_code = ?",
                Integer.class, market.get("legal_entity"), market.get("country"));
        if (entity == null || entity == 0) blockers.add("legal entity " + market.get("legal_entity") + " is missing, inactive or in another country");
        List<String> legalName = market.get("legal_entity_name") != null ? List.of(String.valueOf(market.get("legal_entity_name")))
                : jdbc.queryForList("SELECT legal_name FROM ref_legal_entities WHERE code = ?", String.class, market.get("legal_entity"));
        if (!legalName.isEmpty() && PLACEHOLDER.matcher(legalName.get(0)).matches()) blockers.add("the legal entity's name is a placeholder: " + legalName.get(0));
        boolean anyLive = false;
        for (Map<String, Object> c : currencies) {
            boolean live = Boolean.TRUE.equals(c.get("live")) || Integer.valueOf(1).equals(c.get("live"));
            if (!live) continue;
            anyLive = true;
            if (PLACEHOLDER.matcher(String.valueOf(c.get("bank_account_number"))).matches()) {
                blockers.add("the " + c.get("currency") + " bank account is a placeholder");
            }
        }
        if (!anyLive) blockers.add("no currency is marked live");
        if (!payoutFormats.supports(String.valueOf(market.get("payout_format")))) blockers.add("no adapter for payout format " + market.get("payout_format"));
        try {
            for (JsonNode l : json.readTree(String.valueOf(market.get("required_lists_json")))) {
                List<Integer> v = jdbc.queryForList("SELECT version FROM screening_lists WHERE code = ? AND status = 'ACTIVE'", Integer.class, l.asText());
                if (v.isEmpty() || v.get(0) == 0) blockers.add("required screening list " + l.asText() + " is not loaded");
            }
        } catch (Exception e) {
            blockers.add("required screening lists unreadable");
        }
        if ("NOT_CONFIGURED".equals(market.get("fiu_report_format"))) warnings.add("no FIU report format configured for this country");
        Integer cal = jdbc.queryForObject("SELECT COUNT(*) FROM compliance_parameters WHERE param_key = ?", Integer.class, market.get("holidays_parameter"));
        if (cal == null || cal == 0) warnings.add("no public-holiday calendar (" + market.get("holidays_parameter") + "): working-day deadlines count weekends only");
        if (market.get("bank_bic") == null) warnings.add("no BIC for the sponsor bank");
        if (String.valueOf(market.get("bank_format_status")).startsWith("PROVISIONAL") || "SANDBOX".equals(market.get("bank_format_status"))) {
            warnings.add("bank formats not confirmed: " + market.get("bank_format_status"));
        }
        return Map.of("readyForLive", blockers.isEmpty(), "blockers", blockers, "warnings", warnings);
    }

    // ─── requests (two people) ───────────────────────────────────────────────

    /**
     * changes: market fields (see MARKET_FIELDS). currencies: per-currency changes or additions
     * (currency, enabled, live, symbol, checkoutProductType, bankAccountNumber, bankAccountName,
     * ledgerOwner; plus name and minorUnits for a currency new to PayPort).
     */
    public Map<String, Object> requestChange(String country, Map<String, Object> changes, List<Map<String, Object>> currencies, String reason, String by) {
        String cc = country.toUpperCase(Locale.ROOT);
        Map<String, Object> current = jdbc.queryForMap("SELECT * FROM markets WHERE country = ?", cc);
        Map<String, Object> ch = changes == null ? Map.of() : changes;
        List<Map<String, Object>> cur = currencies == null ? List.of() : currencies;
        for (String k : ch.keySet()) if (!MARKET_FIELDS.contains(k)) throw new IllegalArgumentException("Unknown market setting: " + k);
        for (Map<String, Object> c : cur) {
            for (String k : c.keySet()) if (!CURRENCY_FIELDS.contains(k)) throw new IllegalArgumentException("Unknown currency setting: " + k);
            if (c.get("currency") == null) throw new IllegalArgumentException("currency is required for each currency change");
        }
        if (ch.isEmpty() && cur.isEmpty()) throw new IllegalArgumentException("Nothing to change");
        List<Map<String, Object>> curRows = jdbc.queryForList("SELECT * FROM market_currencies WHERE country = ?", cc);
        Proposed p = propose(current, curRows, ch, cur);
        List<String> problems = MarketRegistry.validate(p.market());
        if (!problems.isEmpty()) throw new IllegalArgumentException("The change would leave " + cc + " invalid: " + problems);
        // Readiness blocks the move to LIVE. A market that is already live is not frozen by its own
        // provisional settings (e.g. a sponsor-bank account still to be confirmed): those become
        // warnings for the approver, so switching a currency back on after an incident is never blocked.
        if ("LIVE".equals(p.market().status()) && !"LIVE".equals(current.get("status"))) {
            @SuppressWarnings("unchecked") List<String> blockers = (List<String>) readiness(p.row(), p.currencyRows()).get("blockers");
            if (!blockers.isEmpty()) throw new IllegalArgumentException(cc + " cannot go live yet: " + blockers);
        }
        boolean enablesMoney = enablesMoney(current, curRows, p);
        String role = enablesMoney ? "COMPLIANCE" : "ADMIN";
        ObjectNode payload = json.createObjectNode();
        payload.put("country", cc);
        payload.put("fromVersion", ((Number) current.get("version")).intValue());
        payload.set("changes", json.valueToTree(ch));
        payload.set("currencies", json.valueToTree(cur));
        payload.put("enablesMoney", enablesMoney);
        String body = payload.toString();
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                ApprovalStore.request(c, ACTION_CHANGE, "market:" + cc, body, reason, role, by, 72));
        audit.record(by, "MARKET_CHANGE_REQUESTED", "market:" + cc, body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", a.id());
        out.put("approvalRef", a.ref());
        out.put("requiredRole", role);
        out.put("enablesMoney", enablesMoney);
        Map<String, Object> r = readiness(p.row(), p.currencyRows());
        List<Object> warnings = new ArrayList<>((List<?>) r.get("warnings"));
        if ("LIVE".equals(current.get("status"))) warnings.addAll((List<?>) r.get("blockers"));
        out.put("warnings", warnings);
        return out;
    }

    /** A new market always starts in SANDBOX. definition: country, countryName, legalEntity, legalEntityName, currencies[...], other settings. */
    public Map<String, Object> requestNewMarket(Map<String, Object> definition, String reason, String by) {
        String cc = String.valueOf(definition.get("country")).toUpperCase(Locale.ROOT);
        if (!cc.matches("^[A-Z]{2}$")) throw new IllegalArgumentException("country must be an ISO 3166-1 alpha-2 code");
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM markets WHERE country = ?", Integer.class, cc);
        if (exists != null && exists > 0) throw new IllegalArgumentException(cc + " already exists; request a change instead");
        for (String k : List.of("countryName", "name", "legalEntity", "legalEntityName", "timezone", "phoneCountryCode", "nationalPattern", "bankName", "payoutFormat")) {
            if (definition.get(k) == null || String.valueOf(definition.get(k)).isBlank()) throw new IllegalArgumentException(k + " is required");
        }
        Object ccys = definition.get("currencies");
        if (!(ccys instanceof List<?> l) || l.isEmpty()) throw new IllegalArgumentException("at least one currency is required");
        Map<String, Object> d = new LinkedHashMap<>(definition);
        d.put("country", cc);
        d.put("status", "SANDBOX");
        // validate by proposing against an empty market
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("country", cc);
        empty.put("version", 0);
        @SuppressWarnings("unchecked") List<Map<String, Object>> cl = (List<Map<String, Object>>) ccys;
        for (Map<String, Object> c : cl) {
            if (Boolean.TRUE.equals(c.get("live"))) throw new IllegalArgumentException("a new market starts in SANDBOX: no currency can be live");
        }
        Proposed p = propose(defaults(empty, d), List.of(), filter(d, MARKET_FIELDS), cl);
        List<String> problems = MarketRegistry.validate(p.market());
        if (!problems.isEmpty()) throw new IllegalArgumentException("The new market is invalid: " + problems);
        String body = json.valueToTree(d).toString();
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c ->
                ApprovalStore.request(c, ACTION_CREATE, "market:" + cc, body, reason, "ADMIN", by, 72));
        audit.record(by, "MARKET_CREATE_REQUESTED", "market:" + cc, body);
        return Map.of("approvalId", a.id(), "approvalRef", a.ref(), "requiredRole", "ADMIN");
    }

    @Transactional
    public Map<String, Object> decide(long approvalId, boolean approve, String note, String by, Set<String> roles) {
        Map<String, Object> pending = jdbc.queryForMap("SELECT action_type, required_role FROM platform_approvals WHERE id = ?", approvalId);
        String action = (String) pending.get("action_type");
        if (!ACTION_CHANGE.equals(action) && !ACTION_CREATE.equals(action)) throw new IllegalArgumentException("Not a market request");
        // A change that enables money needs a real compliance officer (ApprovalStore would also accept ADMIN).
        if ("COMPLIANCE".equals(pending.get("required_role")) && !roles.contains("COMPLIANCE")) {
            throw new zw.co.payport.gateway.platform.PlatformException("APPROVAL_ROLE", "Enabling money in a market needs a COMPLIANCE officer's approval");
        }
        ApprovalStore.Approval a = jdbc.execute((ConnectionCallback<ApprovalStore.Approval>) c -> ApprovalStore.decide(c, approvalId, approve, by, roles, note));
        String cc;
        try {
            JsonNode p = json.readTree(a.payload());
            cc = p.path("country").asText();
            if (approve) {
                if (ACTION_CREATE.equals(action)) applyCreate(p, a.requestedBy() + " / " + by);
                else {
                    String before = jdbc.queryForObject("SELECT status FROM markets WHERE country = ?", String.class, cc);
                    applyChange(p, a.requestedBy() + " / " + by);
                    // Readiness again at the moment of approval (a screening list may have lapsed since the request).
                    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM markets WHERE country = ?", cc);
                    if ("LIVE".equals(row.get("status")) && !"LIVE".equals(before)) {
                        @SuppressWarnings("unchecked") List<String> blockers = (List<String>) readiness(row,
                                jdbc.queryForList("SELECT * FROM market_currencies WHERE country = ?", cc)).get("blockers");
                        if (!blockers.isEmpty()) throw new IllegalStateException(cc + " cannot go live: " + blockers);
                    }
                }
                List<String> problems = registry.loadProblems(cc);
                if (!problems.isEmpty()) throw new IllegalStateException("The change would withdraw " + cc + ": " + problems);
                reloadAfterCommit();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        audit.record(by, (ACTION_CREATE.equals(action) ? "MARKET_CREATE_" : "MARKET_CHANGE_") + (approve ? "APPROVED" : "REJECTED"), "market:" + cc, a.payload());
        return Map.of("approvalId", a.id(), "status", a.status(), "country", cc);
    }

    // ─── immediate switches (one person) ─────────────────────────────────────

    @Transactional
    public Map<String, Object> suspend(String country, String reason, String by) {
        reason = orDefault(reason, "Suspended from the ops console");
        String cc = country.toUpperCase(Locale.ROOT);
        int n = jdbc.update("UPDATE markets SET status = 'SUSPENDED', status_note = ?, version = version + 1, updated_by = ?, updated_at = ? WHERE country = ?",
                reason, by, now(), cc);
        if (n == 0) throw new IllegalArgumentException("Unknown market " + cc);
        audit.record(by, "MARKET_SUSPENDED", "market:" + cc, reason);
        reloadAfterCommit();
        log.warn("[MARKETS] {} suspended by {}: {}", cc, by, reason);
        return Map.of("country", cc, "status", "SUSPENDED");
    }

    /** enabled=false stops all new payments in the currency; liveOnly=true only stops live money. */
    @Transactional
    public Map<String, Object> switchOffCurrency(String currency, boolean liveOnly, String reason, String by) {
        reason = orDefault(reason, "Switched off from the ops console");
        String ccy = currency.toUpperCase(Locale.ROOT);
        int n = liveOnly
                ? jdbc.update("UPDATE market_currencies SET live = FALSE, updated_by = ?, updated_at = ? WHERE currency = ?", by, now(), ccy)
                : jdbc.update("UPDATE market_currencies SET live = FALSE, enabled = FALSE, updated_by = ?, updated_at = ? WHERE currency = ?", by, now(), ccy);
        if (n == 0) throw new IllegalArgumentException("Unknown currency " + ccy);
        audit.record(by, liveOnly ? "CURRENCY_LIVE_OFF" : "CURRENCY_DISABLED", "currency:" + ccy, reason);
        reloadAfterCommit();
        log.warn("[MARKETS] currency {} {} by {}: {}", ccy, liveOnly ? "live money switched off" : "disabled", by, reason);
        return Map.of("currency", ccy, "enabled", liveOnly, "live", false);
    }

    // ─── apply ───────────────────────────────────────────────────────────────

    private void applyChange(JsonNode p, String by) {
        String cc = p.path("country").asText();
        Map<String, Object> current = jdbc.queryForMap("SELECT * FROM markets WHERE country = ? FOR UPDATE", cc);
        if (((Number) current.get("version")).intValue() != p.path("fromVersion").asInt()) {
            throw new IllegalStateException(cc + " changed since this request was made; request again");
        }
        JsonNode ch = p.path("changes");
        Map<String, Object> next = new LinkedHashMap<>(current);
        ch.fields().forEachRemaining(e -> next.put(column(e.getKey()), value(e.getKey(), e.getValue())));
        if (ch.has("legalEntityName")) {
            Map<String, Object> e = jdbc.queryForMap("SELECT * FROM ref_legal_entities WHERE code = ?", current.get("legal_entity"));
            jdbc.update("CALL ref_upsert_legal_entity(?, ?, ?, ?)", e.get("code"), ch.path("legalEntityName").asText(), e.get("country_code"), e.get("base_currency"));
        }
        jdbc.update("UPDATE markets SET name = ?, status = ?, status_note = ?, timezone = ?, phone_country_code = ?, national_pattern = ?, holidays_parameter = ?, "
                        + "fiu_report_format = ?, regulators_json = ?, required_lists_json = ?, bank_name = ?, bank_bic = ?, payout_format = ?, statement_format = ?, "
                        + "statement_guideline = ?, bank_format_status = ?, version = version + 1, updated_by = ?, updated_at = ? WHERE country = ?",
                next.get("name"), next.get("status"), next.get("status_note"), next.get("timezone"), next.get("phone_country_code"), next.get("national_pattern"),
                next.get("holidays_parameter"), next.get("fiu_report_format"), next.get("regulators_json"), next.get("required_lists_json"), next.get("bank_name"),
                blankToNull(next.get("bank_bic")), next.get("payout_format"), next.get("statement_format"), blankToNull(next.get("statement_guideline")),
                next.get("bank_format_status"), by, now(), cc);
        applyCurrencies(cc, p.path("currencies"), by);
        syncReferenceData(cc, String.valueOf(next.get("status")));
    }

    private void applyCreate(JsonNode d, String by) {
        String cc = d.path("country").asText();
        jdbc.update("CALL ref_upsert_country(?, ?)", cc, d.path("countryName").asText());
        JsonNode first = d.path("currencies").get(0);
        for (JsonNode c : d.path("currencies")) {
            jdbc.update("CALL ref_upsert_currency(?, ?, ?)", c.path("currency").asText(), c.path("name").asText(c.path("currency").asText()), c.path("minorUnits").asInt(2));
        }
        jdbc.update("CALL ref_upsert_legal_entity(?, ?, ?, ?)", d.path("legalEntity").asText(), d.path("legalEntityName").asText(), cc, first.path("currency").asText());
        jdbc.update("INSERT INTO markets (country, name, status, status_note, legal_entity, timezone, phone_country_code, national_pattern, holidays_parameter, "
                        + "fiu_report_format, regulators_json, required_lists_json, bank_name, bank_bic, payout_format, statement_format, statement_guideline, "
                        + "bank_format_status, updated_by, updated_at) VALUES (?, ?, 'SANDBOX', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                cc, d.path("name").asText(), d.path("statusNote").asText(null), d.path("legalEntity").asText(), d.path("timezone").asText(),
                d.path("phoneCountryCode").asText(), d.path("nationalPattern").asText(), d.path("holidaysParameter").asText("CALENDAR." + cc + ".HOLIDAYS"),
                d.path("fiuReportFormat").asText("NOT_CONFIGURED"), d.has("regulators") ? d.path("regulators").toString() : "{}",
                d.has("requiredScreeningLists") ? d.path("requiredScreeningLists").toString() : "[\"UN-CONSOLIDATED\"]", d.path("bankName").asText(),
                blankToNull(d.path("bankBic").asText(null)), d.path("payoutFormat").asText(), d.path("statementFormat").asText("ISO20022_CAMT_053_001_08"),
                blankToNull(d.path("statementGuideline").asText(null)), d.path("bankFormatStatus").asText("SANDBOX"), by, now());
        applyCurrencies(cc, d.path("currencies"), by);
        syncReferenceData(cc, "SANDBOX");
    }

    private void applyCurrencies(String cc, JsonNode list, String by) {
        for (JsonNode c : list) {
            String ccy = c.path("currency").asText().toUpperCase(Locale.ROOT);
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM market_currencies WHERE country = ? AND currency = ?", cc, ccy);
            if (rows.isEmpty()) {
                jdbc.update("CALL ref_upsert_currency(?, ?, ?)", ccy, c.path("name").asText(ccy), c.path("minorUnits").asInt(2));
                jdbc.update("INSERT INTO market_currencies (country, currency, enabled, live, symbol, checkout_product_type, bank_account_number, bank_account_name, "
                                + "ledger_owner, iso_numeric, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        cc, ccy, c.path("enabled").asBoolean(false), c.path("live").asBoolean(false), c.path("symbol").asText(ccy + " "),
                        c.path("checkoutProductType").asText("MERCHANT_CHECKOUT"), c.path("bankAccountNumber").asText(), c.path("bankAccountName").asText(),
                        c.path("ledgerOwner").asText("SETTLEMENT_" + ccy), c.path("isoNumeric").asText(null), by, now());
            } else {
                Map<String, Object> r = rows.get(0);
                jdbc.update("UPDATE market_currencies SET enabled = ?, live = ?, symbol = ?, checkout_product_type = ?, bank_account_number = ?, bank_account_name = ?, "
                                + "ledger_owner = ?, iso_numeric = ?, updated_by = ?, updated_at = ? WHERE country = ? AND currency = ?",
                        c.has("enabled") ? c.path("enabled").asBoolean() : r.get("enabled"), c.has("live") ? c.path("live").asBoolean() : r.get("live"),
                        c.has("symbol") ? c.path("symbol").asText() : r.get("symbol"),
                        c.has("checkoutProductType") ? c.path("checkoutProductType").asText() : r.get("checkout_product_type"),
                        c.has("bankAccountNumber") ? c.path("bankAccountNumber").asText() : r.get("bank_account_number"),
                        c.has("bankAccountName") ? c.path("bankAccountName").asText() : r.get("bank_account_name"),
                        c.has("ledgerOwner") ? c.path("ledgerOwner").asText() : r.get("ledger_owner"),
                        c.has("isoNumeric") ? c.path("isoNumeric").asText() : r.get("iso_numeric"), by, now(), cc, ccy);
            }
        }
    }

    /** LIVE: the country and its live currencies are enabled in the ledger's reference data; SANDBOX: the country is not. */
    private void syncReferenceData(String cc, String status) {
        if ("LIVE".equals(status)) {
            jdbc.update("CALL ref_set_country_enabled(?, TRUE)", cc);
            for (String ccy : jdbc.queryForList("SELECT currency FROM market_currencies WHERE country = ? AND live = TRUE", String.class, cc)) {
                jdbc.update("CALL ref_enable_currency(?)", ccy);
            }
        } else if ("SANDBOX".equals(status)) {
            jdbc.update("CALL ref_set_country_enabled(?, FALSE)", cc);
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    record Proposed(MarketRegistry.Market market, Map<String, Object> row, List<Map<String, Object>> currencyRows) {}

    Proposed propose(Map<String, Object> current, List<Map<String, Object>> currentCurrencies, Map<String, Object> changes, List<Map<String, Object>> currencyChanges) {
        Map<String, Object> row = new LinkedHashMap<>(current);
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            if (MARKET_FIELDS.contains(e.getKey())) row.put(column(e.getKey()), value(e.getKey(), json.valueToTree(e.getValue())));
        }
        Map<String, Map<String, Object>> ccy = new LinkedHashMap<>();
        for (Map<String, Object> r : currentCurrencies) ccy.put((String) r.get("currency"), new LinkedHashMap<>(r));
        for (Map<String, Object> c : currencyChanges) {
            String code = String.valueOf(c.get("currency")).toUpperCase(Locale.ROOT);
            Map<String, Object> r = ccy.computeIfAbsent(code, k -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("currency", k);
                n.put("enabled", false);
                n.put("live", false);
                n.put("symbol", k + " ");
                n.put("checkout_product_type", "MERCHANT_CHECKOUT");
                n.put("ledger_owner", "SETTLEMENT_" + k);
                n.put("new", true);
                return n;
            });
            if (c.containsKey("enabled")) r.put("enabled", c.get("enabled"));
            if (c.containsKey("live")) r.put("live", c.get("live"));
            if (c.containsKey("symbol")) r.put("symbol", c.get("symbol"));
            if (c.containsKey("checkoutProductType")) r.put("checkout_product_type", c.get("checkoutProductType"));
            if (c.containsKey("bankAccountNumber")) r.put("bank_account_number", c.get("bankAccountNumber"));
            if (c.containsKey("bankAccountName")) r.put("bank_account_name", c.get("bankAccountName"));
            if (c.containsKey("ledgerOwner")) r.put("ledger_owner", c.get("ledgerOwner"));
            if (c.containsKey("isoNumeric")) r.put("iso_numeric", c.get("isoNumeric"));
        }
        List<MarketRegistry.Currency> currencies = new ArrayList<>();
        Map<String, MarketRegistry.BankAccount> accounts = new LinkedHashMap<>();
        for (Map<String, Object> r : ccy.values()) {
            String code = (String) r.get("currency");
            currencies.add(new MarketRegistry.Currency(code, truthy(r.get("enabled")), truthy(r.get("live")), String.valueOf(r.get("symbol")),
                    String.valueOf(r.get("checkout_product_type")), r.get("iso_numeric") == null ? null : String.valueOf(r.get("iso_numeric"))));
            accounts.put(code, new MarketRegistry.BankAccount(code, r.get("bank_account_number") == null ? "" : String.valueOf(r.get("bank_account_number")),
                    String.valueOf(r.get("bank_account_name")), String.valueOf(r.get("ledger_owner"))));
        }
        Map<String, String> regs = new LinkedHashMap<>();
        MarketRegistry.tree(json, String.valueOf(row.getOrDefault("regulators_json", "{}"))).fields().forEachRemaining(e -> regs.put(e.getKey(), e.getValue().asText()));
        List<String> lists = new ArrayList<>();
        MarketRegistry.tree(json, String.valueOf(row.getOrDefault("required_lists_json", "[]"))).forEach(x -> lists.add(x.asText()));
        Pattern pattern;
        try { pattern = Pattern.compile(String.valueOf(row.get("national_pattern"))); }
        catch (Exception e) { throw new IllegalArgumentException("nationalPattern is not a valid regular expression"); }
        MarketRegistry.Market m = new MarketRegistry.Market(String.valueOf(row.get("country")), String.valueOf(row.get("name")), String.valueOf(row.get("status")),
                (String) row.get("status_note"), String.valueOf(row.get("legal_entity")), String.valueOf(row.get("timezone")),
                String.valueOf(row.get("phone_country_code")), pattern, currencies, String.valueOf(row.get("holidays_parameter")), regs,
                String.valueOf(row.get("fiu_report_format")), lists,
                new MarketRegistry.Bank(String.valueOf(row.get("bank_name")), (String) blankToNull(row.get("bank_bic")), String.valueOf(row.get("payout_format")),
                        String.valueOf(row.get("statement_format")), (String) blankToNull(row.get("statement_guideline")), (String) row.get("bank_format_status"), accounts),
                ((Number) row.getOrDefault("version", 0)).intValue());
        return new Proposed(m, row, new ArrayList<>(ccy.values()));
    }

    private static boolean enablesMoney(Map<String, Object> current, List<Map<String, Object>> curRows, Proposed p) {
        if ("LIVE".equals(p.market().status()) && !"LIVE".equals(current.get("status"))) return true;
        for (Map<String, Object> r : p.currencyRows()) {
            if (Boolean.TRUE.equals(r.get("new"))) return true;
            String code = (String) r.get("currency");
            Map<String, Object> before = curRows.stream().filter(x -> code.equals(x.get("currency"))).findFirst().orElse(Map.of());
            if (truthy(r.get("live")) && !truthy(before.get("live"))) return true;
            if (truthy(r.get("enabled")) && !truthy(before.get("enabled"))) return true;
            if (!String.valueOf(r.get("bank_account_number")).equals(String.valueOf(before.get("bank_account_number")))) return true;  // where money goes
        }
        return false;
    }

    private Map<String, Object> defaults(Map<String, Object> base, Map<String, Object> d) {
        Map<String, Object> row = new LinkedHashMap<>(base);
        row.put("status", "SANDBOX");
        row.put("legal_entity", d.get("legalEntity"));
        row.put("holidays_parameter", d.getOrDefault("holidaysParameter", "CALENDAR." + base.get("country") + ".HOLIDAYS"));
        row.put("fiu_report_format", d.getOrDefault("fiuReportFormat", "NOT_CONFIGURED"));
        row.put("regulators_json", json.valueToTree(d.getOrDefault("regulators", Map.of())).toString());
        row.put("required_lists_json", json.valueToTree(d.getOrDefault("requiredScreeningLists", List.of("UN-CONSOLIDATED"))).toString());
        row.put("statement_format", d.getOrDefault("statementFormat", "ISO20022_CAMT_053_001_08"));
        return row;
    }

    private static Map<String, Object> filter(Map<String, Object> d, Set<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        d.forEach((k, v) -> { if (keys.contains(k) && !"status".equals(k)) out.put(k, v); });
        return out;
    }

    static String column(String field) {
        return switch (field) {
            case "statusNote" -> "status_note";
            case "legalEntityName" -> "legal_entity_name";
            case "phoneCountryCode" -> "phone_country_code";
            case "nationalPattern" -> "national_pattern";
            case "holidaysParameter" -> "holidays_parameter";
            case "fiuReportFormat" -> "fiu_report_format";
            case "regulators" -> "regulators_json";
            case "requiredScreeningLists" -> "required_lists_json";
            case "bankName" -> "bank_name";
            case "bankBic" -> "bank_bic";
            case "payoutFormat" -> "payout_format";
            case "statementFormat" -> "statement_format";
            case "statementGuideline" -> "statement_guideline";
            case "bankFormatStatus" -> "bank_format_status";
            default -> field;
        };
    }

    private static Object value(String field, JsonNode v) {
        if ("regulators".equals(field) || "requiredScreeningLists".equals(field)) return v.isTextual() ? v.asText() : v.toString();
        if ("status".equals(field)) {
            String s = v.asText().toUpperCase(Locale.ROOT);
            if (!MarketRegistry.STATUSES.contains(s)) throw new IllegalArgumentException("status must be LIVE, SANDBOX or SUSPENDED");
            return s;
        }
        return v.isNull() ? null : v.asText();
    }

    private static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o) || (o instanceof Number n && n.intValue() != 0) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static Object blankToNull(Object o) {
        return o == null || String.valueOf(o).isBlank() || "null".equals(String.valueOf(o)) ? null : o;
    }

    private static String orDefault(String reason, String d) {
        return reason == null || reason.isBlank() ? d : reason.trim();
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }

    private void reloadAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { registry.reload(); }
            });
        } else {
            registry.reload();
        }
    }
}
