package zw.co.payport.commerce;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Custom domains (shop.yourbusiness.co.zw). A merchant adds the domain and gets a token; it is verified only when the
 * DNS TXT record _payport.<domain> holds that token (proof that the merchant controls the domain). HTTPS certificates
 * are only issued for verified domains (Caddy asks /internal/tls/allowed before it requests one), a domain belongs to
 * one store at a time, and removing it stops both the certificate and the routing at once.
 */
@Service
public class Domains {

    /** Reads TXT records (replaced in tests). */
    public interface TxtLookup {
        List<String> txt(String name) throws Exception;
    }

    @Component
    public static class DnsTxt implements TxtLookup {
        public List<String> txt(String name) throws Exception {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            List<String> out = new ArrayList<>();
            InitialDirContext ctx = new InitialDirContext(env);
            try {
                Attributes a = ctx.getAttributes(name, new String[] { "TXT" });
                Attribute t = a.get("TXT");
                if (t != null) for (int i = 0; i < t.size(); i++) out.add(String.valueOf(t.get(i)).replace("\"", "").trim());
            } finally {
                ctx.close();
            }
            return out;
        }
    }

    private final JdbcTemplate jdbc;
    private final Shop shop;
    private final TxtLookup dns;

    public Domains(JdbcTemplate jdbc, Shop shop, TxtLookup dns) {
        this.jdbc = jdbc;
        this.shop = shop;
        this.dns = dns;
    }

    static String clean(String raw) {
        String d = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceAll("/.*$", "").replaceAll("\\.$", "");
        if (!d.matches("(?=.{4,190}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}")) throw new IllegalArgumentException("Enter a domain such as shop.yourbusiness.co.zw");
        if (d.equals("payport.africa") || d.endsWith(".payport.africa")) throw new IllegalArgumentException("Use your own domain");
        return d;
    }

    Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", ((Number) r.get("id")).longValue());
        v.put("domain", r.get("domain"));
        v.put("status", r.get("status"));
        v.put("txtName", "_payport." + r.get("domain"));
        v.put("txtValue", "payport-verify=" + r.get("token"));
        v.put("verifiedAt", r.get("verified_at") == null ? null : String.valueOf(r.get("verified_at")));
        v.put("lastError", r.get("last_error"));
        return v;
    }

    public List<Map<String, Object>> list(long merchantId) {
        return jdbc.queryForList("SELECT * FROM store_domains WHERE merchant_id = ? ORDER BY id", merchantId).stream().map(this::view).toList();
    }

    public Map<String, Object> add(long merchantId, String raw) {
        String d = clean(raw);
        long store = ((Number) shop.store(merchantId).get("id")).longValue();
        Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM store_domains WHERE merchant_id = ?", Integer.class, merchantId);
        if (mine != null && mine >= 3) throw new IllegalStateException("At most three domains per store");
        try {
            jdbc.update("INSERT INTO store_domains (merchant_id, store_id, domain, token, status, created_at) VALUES (?,?,?,?, 'PENDING', ?)",
                    merchantId, store, d, Shop.sha256(Shop.token()).substring(0, 32), Shop.now());
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("That domain is already connected to a store");
        }
        shop.event("domain.added", merchantId, d, null);
        return view(jdbc.queryForMap("SELECT * FROM store_domains WHERE domain = ?", d));
    }

    /** Checks the TXT record now. */
    public Map<String, Object> verify(long merchantId, long id) {
        Map<String, Object> r = jdbc.queryForList("SELECT * FROM store_domains WHERE id = ? AND merchant_id = ?", id, merchantId).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No such domain"));
        String want = "payport-verify=" + r.get("token");
        String error = null;
        boolean ok = false;
        try {
            ok = dns.txt("_payport." + r.get("domain")).stream().anyMatch(want::equals);
            if (!ok) error = "The TXT record was not found yet (DNS changes can take up to an hour)";
        } catch (Exception e) {
            error = "The TXT record was not found yet (DNS changes can take up to an hour)";
        }
        jdbc.update("UPDATE store_domains SET status = ?, checked_at = ?, verified_at = CASE WHEN ? THEN COALESCE(verified_at, ?) ELSE verified_at END, last_error = ? WHERE id = ?",
                ok ? "VERIFIED" : "VERIFIED".equals(r.get("status")) ? "VERIFIED" : "PENDING", Shop.now(), ok, Shop.now(), error, id);
        if (ok) shop.event("domain.verified", merchantId, String.valueOf(r.get("domain")), null);
        return view(jdbc.queryForMap("SELECT * FROM store_domains WHERE id = ?", id));
    }

    public void remove(long merchantId, long id) {
        if (jdbc.update("DELETE FROM store_domains WHERE id = ? AND merchant_id = ?", id, merchantId) == 0) throw new Shop.NotFound("No such domain");
    }

    /** For Caddy: may a certificate be issued for this host? Only for a verified domain. */
    public boolean allowed(String host) {
        if (host == null) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM store_domains WHERE domain = ? AND status = 'VERIFIED'", Integer.class, host.toLowerCase(Locale.ROOT));
        return n != null && n > 0;
    }

    /** The store slug a verified custom domain shows. */
    public Optional<String> storeFor(String host) {
        if (host == null || host.isBlank()) return Optional.empty();
        String h = host.toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "");
        return jdbc.queryForList("SELECT s.slug FROM store_domains d JOIN stores s ON s.id = d.store_id WHERE d.domain = ? AND d.status = 'VERIFIED'", String.class, h)
                .stream().findFirst();
    }
}
