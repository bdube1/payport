package zw.co.payport.gateway.roles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every permission in the ops console. Each feature has two permissions, {@code <key>.view} and {@code <key>.manage}:
 * view opens the pages and reads; manage also allows every change on them. A feature lists the console pages it
 * covers, the API paths behind them, and the built-in roles that hold it by default (what those roles could reach
 * before roles became configurable).
 */
public final class PermissionCatalog {

    private PermissionCatalog() {}

    public static final String ADMIN = "ADMIN", OPS = "OPS", FINANCE = "FINANCE", COMPLIANCE = "COMPLIANCE";
    public static final List<String> BUILT_IN = List.of(ADMIN, OPS, FINANCE, COMPLIANCE);
    public static final Map<String, String> BUILT_IN_NAMES = Map.of(
            ADMIN, "Administrator", OPS, "Operations", FINANCE, "Finance", COMPLIANCE, "Compliance");

    public record Feature(String key, String group, String name, List<String> routes, List<String> api, List<String> roles) {
        public String view() { return key + ".view"; }
        public String manage() { return key + ".manage"; }
    }

    private static final String A = "/api/v1/admin/";

    private static Feature f(String key, String group, String name, List<String> routes, List<String> api, String... roles) {
        return new Feature(key, group, name, routes, api, List.of(roles));
    }

    public static final List<String> GROUPS = List.of("Overview", "Transactions", "Merchants", "Agents", "Customers & wallets",
            "Finance & settlement", "Reports", "Monitoring", "Platform setup", "Compliance", "Administration");

    public static final List<Feature> FEATURES = List.of(
            f("dashboard", "Overview", "Dashboard", List.of("/dashboard"),
                    List.of(A + "dashboard", A + "health-status", A + "provider-health"), ADMIN, OPS, FINANCE),
            f("work_queues", "Overview", "Work queues", List.of("/work-queues"), List.of(A + "platform/work-queues"), ADMIN, OPS, FINANCE),
            f("attention", "Overview", "Attention queue", List.of("/attention"), List.of(A + "dashboard/attention-queue"), ADMIN, OPS),

            f("transactions", "Transactions", "Transactions", List.of("/transactions"),
                    List.of(A + "transactions", A + "transactions-search", A + "transactions-export", A + "merchant-transactions", A + "user-history"),
                    ADMIN, OPS, FINANCE),
            f("customer_statement", "Transactions", "Customer statements", List.of("/statement"), List.of(A + "customer-statement"), ADMIN, OPS, FINANCE),
            f("disputes", "Transactions", "Disputes", List.of("/disputes"), List.of(A + "disputes", A + "dispute-timelines"), ADMIN, OPS),
            f("queries", "Transactions", "Queries", List.of("/queries"), List.of(A + "queries"), ADMIN, OPS),

            f("merchants", "Merchants", "Merchants & applications", List.of("/merchants", "/merchants/dashboard"),
                    List.of(A + "merchants", A + "platform/merchants", A + "upload-logo"), ADMIN, OPS),
            f("kyb", "Merchants", "KYB reviews", List.of("/kyb"), List.of(A + "platform/kyb"), ADMIN, OPS),
            f("merchant_statement", "Merchants", "Merchant statements", List.of("/merchants/statement"), List.of(), ADMIN, OPS, FINANCE),
            f("codes", "Merchants", "Merchant & agent codes", List.of("/merchants/codes", "/agents/codes"), List.of(A + "codes"),
                    ADMIN, OPS, FINANCE, COMPLIANCE),
            f("limits", "Merchants", "Limits & charges", List.of("/merchants/limits", "/agents/limits", "/customer-wallets/limits"),
                    List.of(A + "rules"), ADMIN, OPS, FINANCE, COMPLIANCE),
            f("pos", "Merchants", "POS terminals", List.of("/pos-terminals"), List.of(A + "pos"), ADMIN, OPS, COMPLIANCE),

            f("agents", "Agents", "Agents & float", List.of("/agents", "/agents/dashboard"), List.of(A + "agents", A + "float-thresholds"),
                    ADMIN, OPS, FINANCE, COMPLIANCE),

            f("consumers", "Customers & wallets", "Consumers", List.of("/consumers", "/consumers/dashboard"), List.of(A + "consumers"), ADMIN, OPS),
            f("wallets", "Customers & wallets", "Customer wallets", List.of("/customer-wallets", "/customer-wallets/report"), List.of(A + "wallets"),
                    ADMIN, OPS, FINANCE, COMPLIANCE),
            f("cards", "Customers & wallets", "Cards", List.of("/customer-wallets/cards"), List.of(A + "cards"), ADMIN, OPS, FINANCE, COMPLIANCE),

            f("settlements", "Finance & settlement", "Settlements", List.of("/settlements"), List.of(A + "settlements"), ADMIN, OPS),
            f("settlement_batches", "Finance & settlement", "Settlement batches", List.of("/settlement-batches"), List.of(A + "finance/settlement"), ADMIN, FINANCE),
            f("destinations", "Finance & settlement", "Settlement destinations", List.of("/destinations"),
                    List.of(A + "platform/settlement-destinations"), ADMIN, OPS, FINANCE),
            f("reconciliation", "Finance & settlement", "Reconciliation", List.of("/reconciliation", "/statement-reconciliation"),
                    List.of(A + "reconciliation"), ADMIN, OPS),
            f("recon_exceptions", "Finance & settlement", "Recon exceptions", List.of("/recon-exceptions"), List.of(A + "finance/recon"), ADMIN, FINANCE),
            f("bank_messages", "Finance & settlement", "Bank messages", List.of("/bank-messages"), List.of(A + "finance/iso20022"), ADMIN, FINANCE),
            f("provider_settlement", "Finance & settlement", "Provider settlement rules", List.of("/provider-settlement-rules"),
                    List.of(A + "provider-settlement-rules"), ADMIN, FINANCE, COMPLIANCE),
            f("float_balances", "Finance & settlement", "Float balances", List.of("/wallet"), List.of(A + "wallet-balances"), ADMIN, OPS, FINANCE),
            f("ledger", "Finance & settlement", "Ledger", List.of("/ledger"), List.of(A + "ledger"), ADMIN, FINANCE, COMPLIANCE),

            f("analytics", "Reports", "Analytics", List.of("/analytics"), List.of(), ADMIN, OPS, FINANCE),
            f("reports", "Reports", "Consolidated & commission reports", List.of("/reports", "/reports/consolidated", "/reports/commission"),
                    List.of(A + "reports"), ADMIN, OPS, FINANCE),
            f("logs", "Reports", "Audit & notification logs", List.of("/logs"), List.of(A + "logs"), ADMIN, OPS),

            f("integrations", "Monitoring", "Provider status & integrations", List.of("/provider-status", "/integrations"),
                    List.of(A + "integrations", A + "netone", A + "provider-health", A + "health-status"), ADMIN, OPS),
            f("provider_tools", "Monitoring", "Provider tools", List.of("/provider-tools"),
                    List.of(A + "provider-query", "/api/v1/omari/admin", "/api/v1/econet/admin", "/api/v1/vendors"), ADMIN, OPS, FINANCE),

            f("products", "Platform setup", "Products", List.of("/products"), List.of(A + "config/products", A + "config/prefixes"), ADMIN, OPS),
            f("bundles", "Platform setup", "Bundles", List.of("/bundles"), List.of("/api/v1/bundles/admin"), ADMIN, OPS),
            f("channels", "Platform setup", "Channels & product lines", List.of("/channels"), List.of(A + "channels", A + "service-lines"),
                    ADMIN, OPS, FINANCE, COMPLIANCE),
            f("markets", "Platform setup", "Markets, currencies & FX", List.of("/markets"), List.of(A + "markets", A + "fx"), ADMIN, OPS, FINANCE, COMPLIANCE),
            f("ussd_whatsapp", "Platform setup", "USSD & WhatsApp", List.of("/ussd-whatsapp"), List.of(A + "ussd", A + "whatsapp"), ADMIN, OPS, COMPLIANCE),
            f("config", "Platform setup", "Payment methods & configuration", List.of("/config"), List.of(A + "config"), ADMIN, OPS),

            f("compliance", "Compliance", "Compliance console", List.of("/compliance"), List.of(A + "compliance"), COMPLIANCE),
            f("compliance_authorities", "Compliance", "Compliance authorities", List.of("/compliance-authorities"),
                    List.of(A + "compliance-authorities"), ADMIN),

            f("users", "Administration", "Users", List.of("/users"), List.of(A + "users"), ADMIN),
            f("roles", "Administration", "Roles & permissions", List.of("/roles"), List.of(A + "roles"), ADMIN));

    /** Paths outside /api/v1/admin that belong to staff tools (checked for configurable roles). */
    public static final List<String> STAFF_PATHS = List.of("/api/v1/admin/", "/api/v1/omari/admin", "/api/v1/econet/admin",
            "/api/v1/vendors", "/api/v1/bundles/admin");

    private static final List<Map.Entry<String, Feature>> BY_PREFIX;
    static {
        List<Map.Entry<String, Feature>> l = new ArrayList<>();
        for (Feature ft : FEATURES) for (String p : ft.api()) l.add(Map.entry(p, ft));
        l.sort(Comparator.comparingInt((Map.Entry<String, Feature> e) -> e.getKey().length()).reversed());
        BY_PREFIX = List.copyOf(l);
    }

    public static Optional<Feature> feature(String key) {
        return FEATURES.stream().filter(x -> x.key().equals(key)).findFirst();
    }

    private static boolean under(String path, String p) {
        return path.equals(p) || path.startsWith(p.endsWith("/") ? p : p + "/") || (p.endsWith("/") && path.equals(p.substring(0, p.length() - 1)));
    }

    /** The feature that guards an API path (longest matching prefix), if any. */
    public static Optional<Feature> forPath(String path) {
        List<Feature> all = forPathAll(path);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Every feature that shares the longest matching prefix (a path can serve more than one page). */
    public static List<Feature> forPathAll(String path) {
        List<Feature> out = new ArrayList<>();
        int len = -1;
        for (Map.Entry<String, Feature> e : BY_PREFIX) {
            String p = e.getKey();
            if (len >= 0 && p.length() < len) break;
            if (under(path, p)) { len = p.length(); out.add(e.getValue()); }
        }
        return out;
    }

    public static boolean staffPath(String path) {
        return STAFF_PATHS.stream().anyMatch(p -> path.startsWith(p) || path.equals(p.endsWith("/") ? p.substring(0, p.length() - 1) : p));
    }

    public static Set<String> allPermissions() {
        Set<String> s = new LinkedHashSet<>();
        for (Feature ft : FEATURES) { s.add(ft.view()); s.add(ft.manage()); }
        return s;
    }

    /** What a built-in role holds before anyone edits it. */
    public static Set<String> defaults(String role) {
        Set<String> s = new LinkedHashSet<>();
        for (Feature ft : FEATURES) if (ft.roles().contains(role)) { s.add(ft.view()); s.add(ft.manage()); }
        return s;
    }

    /** The catalogue for the console: groups → features → their two permissions. */
    public static List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String g : GROUPS) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Feature ft : FEATURES) {
                if (!ft.group().equals(g)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", ft.key());
                m.put("name", ft.name());
                m.put("view", ft.view());
                m.put("manage", ft.manage());
                m.put("routes", ft.routes());
                m.put("defaultRoles", ft.roles());
                items.add(m);
            }
            out.add(Map.of("group", g, "features", items));
        }
        return out;
    }
}
