package zw.co.payport.gateway.ussd;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The mobile network USSD gateways (Econet, NetOne, Telecel) that call PayPort directly. Each one is
 * switched on and off by ops and must present its access key (generated in ops, shown once, stored
 * hashed) or come from one of its allowed IP addresses. A network that is off, or has neither, is
 * refused: fail closed.
 */
@Service
public class UssdNetworkService {

    public static final List<String> NETWORKS = List.of("ECONET", "NETONE", "TELECEL");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public UssdNetworkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT code, name, enabled, short_code AS shortCode, allowed_ips AS allowedIps, "
                + "access_key_hash IS NOT NULL AS accessKeySet, access_key_hint AS accessKeyHint, updated_by AS updatedBy, updated_at AS updatedAt "
                + "FROM ussd_networks ORDER BY FIELD(code, 'ECONET', 'NETONE', 'TELECEL')");
    }

    public Map<String, Object> get(String code) {
        return list().stream().filter(m -> code(code).equals(m.get("code"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown network " + code));
    }

    public Map<String, Object> update(String code, Boolean enabled, String shortCode, String allowedIps, String by) {
        String c = code(code);
        get(c);
        if (shortCode != null && !shortCode.isBlank() && !shortCode.trim().matches("[*#0-9]{2,20}")) throw new IllegalArgumentException("Short code looks wrong, for example *699#");
        String ips = allowedIps == null ? null : String.join(",", Arrays.stream(allowedIps.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        if (ips != null && ips.length() > 500) throw new IllegalArgumentException("Too many IP addresses");
        jdbc.update("UPDATE ussd_networks SET enabled = COALESCE(?, enabled), short_code = COALESCE(?, short_code), allowed_ips = COALESCE(?, allowed_ips), "
                + "updated_by = ?, updated_at = ? WHERE code = ?", enabled, shortCode == null ? null : shortCode.trim(), ips, by, now(), c);
        return get(c);
    }

    /** A new access key for the network; returned once, stored only as its SHA-256. */
    public Map<String, Object> rotateKey(String code, String by) {
        String c = code(code);
        get(c);
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String key = HexFormat.of().formatHex(b);
        jdbc.update("UPDATE ussd_networks SET access_key_hash = ?, access_key_hint = ?, updated_by = ?, updated_at = ? WHERE code = ?",
                sha256(key), key.substring(key.length() - 4), by, now(), c);
        Map<String, Object> out = new LinkedHashMap<>(get(c));
        out.put("accessKey", key);
        return out;
    }

    /** Null when the call may proceed, otherwise the reason it is refused. */
    public String refuse(String code, String key, String ip) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT enabled, allowed_ips, access_key_hash FROM ussd_networks WHERE code = ?", code(code));
        if (rows.isEmpty()) return "unknown network";
        Map<String, Object> r = rows.get(0);
        if (!Boolean.TRUE.equals(r.get("enabled")) && !Integer.valueOf(1).equals(r.get("enabled"))) return "switched off";
        String hash = (String) r.get("access_key_hash");
        if (hash != null && key != null && !key.isBlank()
                && MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8), sha256(key.trim()).getBytes(StandardCharsets.UTF_8))) return null;
        String ips = (String) r.get("allowed_ips");
        if (ips != null && ip != null && Arrays.asList(ips.split(",")).contains(ip.trim())) return null;
        return hash == null && (ips == null || ips.isBlank()) ? "no access key or IP address configured" : "not authorised";
    }

    static String code(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!NETWORKS.contains(c)) throw new IllegalArgumentException("Unknown network " + code);
        return c;
    }

    /** Reads a gateway's XML without DTDs or external entities; elements are matched by local name. */
    static Document xml(String body) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unreadable request");
        }
    }

    static String text(Document d, String name) {
        NodeList n = d.getElementsByTagNameNS("*", name);
        if (n.getLength() == 0) n = d.getElementsByTagName(name);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }
}
