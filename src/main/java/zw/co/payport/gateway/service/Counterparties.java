package zw.co.payport.gateway.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Transaction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who a payment went to, as the customer should see it: the merchant's name for a merchant payment, the agent's
 * name for an agent transaction, the provider (ZESA, Econet, NetOne, Telecel …) for a service.
 */
@Service
public class Counterparties {

    private static final Map<String, String> SHORT = Map.of(
            "ECONET", "Econet", "NETONE", "NetOne", "TELECEL", "Telecel", "ZESA", "ZESA", "DSTV", "DStv",
            "TELONE", "TelOne", "PAYPORT_WALLET", "PayPort Wallet", "ECOCASH", "EcoCash", "ONEMONEY", "OneMoney");

    private final JdbcTemplate jdbc;
    private final Map<String, String> providers = new ConcurrentHashMap<>();

    public Counterparties(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String forTransaction(Transaction t) {
        if (t == null) return null;
        var p = t.getProductType();
        String code = p == null ? "" : String.valueOf(p.getCode());
        if (code.startsWith("MERCHANT")) {
            String m = merchantOf(t.getReference());
            if (m != null) return m;
        }
        return provider(p == null ? null : p.getProviderCode());
    }

    /** For a wallet operation's reference (a payment's transaction reference). */
    public String forReference(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String m = merchantOf(reference);
        if (m != null) return m;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.provider_code FROM transactions t JOIN product_types p ON p.id = t.product_type_id WHERE t.reference = ? LIMIT 1", reference);
        return rows.isEmpty() ? null : provider((String) rows.get(0).get("provider_code"));
    }

    /** An agent counterparty is recorded as "<code> <name>"; the customer sees the name. */
    public static String agentName(String counterparty) {
        if (counterparty == null) return null;
        String s = counterparty.trim();
        int sp = s.indexOf(' ');
        return sp > 0 && s.substring(0, sp).matches("[A-Z0-9-]+") && s.substring(0, sp).chars().anyMatch(Character::isDigit) ? s.substring(sp + 1).trim() : s;
    }

    private String merchantOf(String reference) {
        if (reference == null) return null;
        List<String> names = jdbc.queryForList("SELECT m.business_name FROM checkout_sessions cs JOIN merchants m ON m.id = cs.merchant_id "
                + "WHERE cs.transaction_reference = ? LIMIT 1", String.class, reference);
        return names.isEmpty() ? null : names.get(0);
    }

    private String provider(String code) {
        if (code == null || code.isBlank()) return null;
        if (SHORT.containsKey(code)) return SHORT.get(code);
        return providers.computeIfAbsent(code, c -> jdbc.queryForList("SELECT name FROM service_providers WHERE code = ?", String.class, c)
                .stream().findFirst().orElse(c));
    }
}
