package zw.co.payport.gateway.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Six-digit codes customers type (or scan) to pay a merchant or cash out at an agent. One allocation table for
 * agents and merchants together (party_codes, code is the primary key), so a code is never given twice, not even
 * to an agent and a merchant. Codes are random (not sequential) and never reused.
 *
 * A party has a main code and may hold more (one per branch). Every code has a static QR: a random token linked
 * to the code; a new QR replaces the old token and stays linked to the same code.
 */
@Slf4j
@Service
public class PartyCodes implements ApplicationRunner {

    public static final String AGENT = "AGENT";
    public static final String MERCHANT = "MERCHANT";
    public static final String QR_PREFIX = "PAYPORT-QR:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] TOKEN = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final JdbcTemplate jdbc;

    public PartyCodes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String type(String partyType) {
        String t = partyType == null ? "" : partyType.trim().toUpperCase(Locale.ROOT);
        if (!AGENT.equals(t) && !MERCHANT.equals(t)) throw new IllegalArgumentException("Codes are for agents or merchants");
        return t;
    }

    static Timestamp now() {
        return Timestamp.valueOf(LocalDateTime.now());
    }

    /** The party's main code, allocating it the first time. */
    public String allocate(String partyType, long partyId) {
        String type = type(partyType);
        Optional<String> existing = codeOf(type, partyId);
        if (existing.isPresent()) return existing.get();
        String code = insert(type, partyId, null, null, true);
        if (AGENT.equals(type)) jdbc.update("UPDATE agents SET code = ? WHERE id = ?", code, partyId);
        else jdbc.update("UPDATE merchants SET merchant_code = ? WHERE id = ?", code, partyId);
        return code;
    }

    /** A further code for the party (e.g. a branch). */
    public String addCode(String partyType, long partyId, String label, String address) {
        String type = type(partyType);
        String l = label == null ? "" : label.trim();
        if (l.isEmpty()) throw new IllegalArgumentException("Give the code a name, for example the branch name");
        if (l.length() > 80) throw new IllegalArgumentException("Keep the name to 80 characters");
        allocate(type, partyId);
        return insert(type, partyId, l, address == null || address.isBlank() ? null : address.trim(), false);
    }

    private String insert(String type, long partyId, String label, String address, boolean primary) {
        for (int attempt = 0; attempt < 50; attempt++) {
            String code = String.valueOf(100_000 + RANDOM.nextInt(900_000));
            try {
                jdbc.update("INSERT INTO party_codes (code, party_type, party_id, label, address, is_primary, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)",
                        code, type, partyId, label, address, primary, now());
                return code;
            } catch (DuplicateKeyException e) {
                if (primary) {
                    Optional<String> raced = codeOf(type, partyId);
                    if (raced.isPresent()) return raced.get();
                }
                // the code belongs to someone else: draw again
            }
        }
        throw new IllegalStateException("Could not allocate a code, please try again");
    }

    /** The party's main code. */
    public Optional<String> codeOf(String partyType, long partyId) {
        return jdbc.queryForList("SELECT code FROM party_codes WHERE party_type = ? AND party_id = ? ORDER BY is_primary DESC, created_at LIMIT 1",
                String.class, type(partyType), partyId).stream().findFirst();
    }

    /** All the party's codes, main code first. */
    public List<Map<String, Object>> codesOf(String partyType, long partyId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT code, label, address, is_primary, status, qr_token, qr_issued_at, created_at "
                + "FROM party_codes WHERE party_type = ? AND party_id = ? ORDER BY is_primary DESC, created_at", type(partyType), partyId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(view(r));
        return out;
    }

    static Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean primary = Boolean.TRUE.equals(r.get("is_primary")) || (r.get("is_primary") instanceof Number n && n.intValue() == 1);
        m.put("code", r.get("code"));
        m.put("label", r.get("label") != null ? r.get("label") : primary ? "Main" : "");
        m.put("address", r.get("address"));
        m.put("primary", primary);
        m.put("status", r.get("status"));
        if (r.get("qr_token") != null) m.put("qrPayload", QR_PREFIX + r.get("qr_token"));
        m.put("qrIssuedAt", r.get("qr_issued_at"));
        m.put("createdAt", r.get("created_at"));
        for (String k : List.of("party_type", "party_id", "party_name")) if (r.containsKey(k)) m.put(camel(k), r.get(k));
        return m;
    }

    static String camel(String k) {
        StringBuilder b = new StringBuilder();
        boolean up = false;
        for (char c : k.toCharArray()) {
            if (c == '_') { up = true; continue; }
            b.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return b.toString();
    }

    /** The party behind an active code, if the code belongs to that kind of party. */
    public Optional<Long> resolve(String partyType, String code) {
        String c = code == null ? "" : code.replaceAll("[^0-9]", "");
        if (c.length() != 6) return Optional.empty();
        return jdbc.queryForList("SELECT party_id FROM party_codes WHERE code = ? AND party_type = ? AND status = 'ACTIVE'", Long.class,
                c, type(partyType)).stream().findFirst();
    }

    /** True when the code is one of the party's codes. */
    public boolean owns(String partyType, long partyId, String code) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM party_codes WHERE code = ? AND party_type = ? AND party_id = ?", Integer.class,
                code == null ? "" : code.trim(), type(partyType), partyId);
        return n != null && n > 0;
    }

    public Optional<Map<String, Object>> row(String code) {
        return jdbc.queryForList("SELECT * FROM party_codes WHERE code = ?", code == null ? "" : code.trim()).stream().findFirst();
    }

    // ─── QR ──────────────────────────────────────────────────────────────────

    /** The code's QR payload, issuing the static QR the first time. */
    public String qr(String partyType, long partyId, String code) {
        if (!owns(partyType, partyId, code)) throw new IllegalArgumentException("No such code");
        String token = jdbc.queryForObject("SELECT qr_token FROM party_codes WHERE code = ?", String.class, code);
        return QR_PREFIX + (token != null ? token : newQr(partyType, partyId, code));
    }

    /** A new QR for the code; the old one stops working. */
    public String newQr(String partyType, long partyId, String code) {
        if (!owns(partyType, partyId, code)) throw new IllegalArgumentException("No such code");
        for (int i = 0; i < 20; i++) {
            StringBuilder t = new StringBuilder();
            for (int k = 0; k < 16; k++) t.append(TOKEN[RANDOM.nextInt(TOKEN.length)]);
            try {
                jdbc.update("UPDATE party_codes SET qr_token = ?, qr_issued_at = ? WHERE code = ?", t.toString(), now(), code);
                return t.toString();
            } catch (DuplicateKeyException e) {
                // draw again
            }
        }
        throw new IllegalStateException("Could not create a QR code, please try again");
    }

    /** The code behind a scanned QR (current QR only), or the code itself when the scan is a plain code. */
    public Optional<Map<String, Object>> fromQr(String scanned) {
        String s = scanned == null ? "" : scanned.trim();
        List<Map<String, Object>> rows;
        if (s.toUpperCase(Locale.ROOT).startsWith(QR_PREFIX)) {
            rows = jdbc.queryForList("SELECT * FROM party_codes WHERE qr_token = ? AND status = 'ACTIVE'", s.substring(QR_PREFIX.length()).trim().toUpperCase(Locale.ROOT));
        } else if (s.matches("[A-Z]{16}")) {
            rows = jdbc.queryForList("SELECT * FROM party_codes WHERE qr_token = ? AND status = 'ACTIVE'", s);
        } else {
            String digits = s.replaceAll("[^0-9]", "");
            rows = digits.length() == 6 ? jdbc.queryForList("SELECT * FROM party_codes WHERE code = ? AND status = 'ACTIVE'", digits) : List.of();
        }
        return rows.stream().findFirst();
    }

    // ─── more codes: applications ────────────────────────────────────────────

    public Map<String, Object> request(String partyType, long partyId, String label, String address, String by) {
        String type = type(partyType);
        String l = label == null ? "" : label.trim();
        if (l.isEmpty()) throw new IllegalArgumentException("Give the code a name, for example the branch name");
        if (l.length() > 80) throw new IllegalArgumentException("Keep the name to 80 characters");
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM party_code_requests WHERE party_type = ? AND party_id = ? AND status = 'PENDING'",
                Integer.class, type, partyId);
        if (open != null && open >= 10) throw new IllegalStateException("You already have 10 applications waiting for approval");
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        String a = address == null || address.isBlank() ? null : address.trim();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement("INSERT INTO party_code_requests (party_type, party_id, label, address, status, requested_by, requested_at) "
                    + "VALUES (?, ?, ?, ?, 'PENDING', ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, type); ps.setLong(2, partyId); ps.setString(3, l); ps.setString(4, a); ps.setString(5, by); ps.setTimestamp(6, now());
            return ps;
        }, keys);
        return jdbc.queryForMap("SELECT * FROM party_code_requests WHERE id = ?", keys.getKey().longValue());
    }

    public List<Map<String, Object>> requestsOf(String partyType, long partyId) {
        return jdbc.queryForList("SELECT * FROM party_code_requests WHERE party_type = ? AND party_id = ? ORDER BY id DESC LIMIT 100", type(partyType), partyId);
    }

    public List<Map<String, Object>> requests(String status) {
        StringBuilder sql = new StringBuilder("SELECT r.*, CASE WHEN r.party_type = 'AGENT' THEN a.name ELSE m.business_name END AS party_name "
                + "FROM party_code_requests r LEFT JOIN agents a ON r.party_type = 'AGENT' AND a.id = r.party_id "
                + "LEFT JOIN merchants m ON r.party_type = 'MERCHANT' AND m.id = r.party_id");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" WHERE r.status = ?"); args.add(status.trim().toUpperCase(Locale.ROOT)); }
        sql.append(" ORDER BY r.status = 'PENDING' DESC, r.id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> decide(long requestId, boolean approve, String note, String by) {
        Map<String, Object> r = jdbc.queryForList("SELECT * FROM party_code_requests WHERE id = ?", requestId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such application"));
        if (!"PENDING".equals(r.get("status"))) throw new IllegalStateException("This application was already " + String.valueOf(r.get("status")).toLowerCase(Locale.ROOT));
        String code = null;
        if (approve) code = addCode((String) r.get("party_type"), ((Number) r.get("party_id")).longValue(), (String) r.get("label"), (String) r.get("address"));
        int n = jdbc.update("UPDATE party_code_requests SET status = ?, code = ?, note = ?, decided_by = ?, decided_at = ? WHERE id = ? AND status = 'PENDING'",
                approve ? "APPROVED" : "REJECTED", code, note == null || note.isBlank() ? null : note.trim(), by, now(), requestId);
        if (n == 0) throw new IllegalStateException("This application was already decided");
        return jdbc.queryForMap("SELECT * FROM party_code_requests WHERE id = ?", requestId);
    }

    /** All codes for the admin portal, with the owner's name. */
    public List<Map<String, Object>> search(String partyType, String q) {
        StringBuilder sql = new StringBuilder("SELECT c.*, CASE WHEN c.party_type = 'AGENT' THEN a.name ELSE m.business_name END AS party_name "
                + "FROM party_codes c LEFT JOIN agents a ON c.party_type = 'AGENT' AND a.id = c.party_id "
                + "LEFT JOIN merchants m ON c.party_type = 'MERCHANT' AND m.id = c.party_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (partyType != null && !partyType.isBlank()) { sql.append(" AND c.party_type = ?"); args.add(type(partyType)); }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            sql.append(" AND (c.code LIKE ? OR c.label LIKE ? OR a.name LIKE ? OR m.business_name LIKE ?)");
            args.addAll(List.of(like, like, like, like));
        }
        sql.append(" ORDER BY party_name, c.is_primary DESC, c.created_at LIMIT 1000");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), args.toArray())) out.add(view(r));
        return out;
    }

    /** Suspends or re-activates a further code (the main code stays active while the party is). */
    public Map<String, Object> setStatus(String code, String status) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "SUSPENDED").contains(s)) throw new IllegalArgumentException("Choose ACTIVE or SUSPENDED");
        Map<String, Object> r = row(code).orElseThrow(() -> new IllegalArgumentException("No such code"));
        if (Boolean.TRUE.equals(r.get("is_primary")) && "SUSPENDED".equals(s)) throw new IllegalStateException("The main code cannot be suspended; suspend the agent or merchant instead");
        jdbc.update("UPDATE party_codes SET status = ? WHERE code = ?", s, code);
        return view(row(code).orElseThrow());
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Long> agents = jdbc.queryForList("SELECT id FROM agents WHERE code IS NULL", Long.class);
            for (Long id : agents) allocate(AGENT, id);
            List<Long> merchants = jdbc.queryForList("SELECT id FROM merchants WHERE merchant_code IS NULL", Long.class);
            for (Long id : merchants) allocate(MERCHANT, id);
            if (!agents.isEmpty() || !merchants.isEmpty()) log.info("[CODES] allocated codes to {} agent(s) and {} merchant(s)", agents.size(), merchants.size());
        } catch (Exception e) {
            log.warn("[CODES] start-up allocation skipped: {}", e.getMessage());
        }
    }
}
