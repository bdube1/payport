package zw.co.payport.gateway.cards;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.risk.ComplianceAudit;
import zw.co.payport.gateway.wallets.WalletService;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Cards linked to wallets: virtual and physical, VISA or ZimSwitch. A request is recorded against the wallet and
 * sent to the scheme's issuer; until the issuer is connected it stays REQUESTED. Status changes (freeze, unfreeze,
 * cancel, block) go through the issuer for issued cards. Card numbers are never stored, only the masked PAN.
 */
@Service
public class CardService {

    public static final List<String> SCHEMES = List.of("VISA", "ZIMSWITCH");
    public static final List<String> FORMS = List.of("VIRTUAL", "PHYSICAL");
    static final Set<String> OPEN = Set.of("REQUESTED", "ACTIVE", "FROZEN");
    static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final WalletService wallets;
    private final ComplianceAudit audit;
    private final Map<String, CardIssuer> issuers = new HashMap<>();

    public CardService(JdbcTemplate jdbc, WalletService wallets, ComplianceAudit audit, @Autowired(required = false) List<CardIssuer> connected) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.audit = audit;
        for (String s : SCHEMES) issuers.put(s, CardIssuer.unconnected(s));
        if (connected != null) for (CardIssuer i : connected) issuers.put(i.scheme(), i);
    }

    public CardIssuer issuer(String scheme) {
        CardIssuer i = issuers.get(scheme);
        if (i == null) throw new IllegalArgumentException("Choose VISA or ZimSwitch");
        return i;
    }

    public Map<String, Object> options() {
        List<Map<String, Object>> schemes = new ArrayList<>();
        for (String s : SCHEMES) schemes.add(Map.of("scheme", s, "connected", issuer(s).connected()));
        return Map.of("schemes", schemes, "forms", FORMS);
    }

    public List<Map<String, Object>> forUser(long userId) {
        return jdbc.queryForList("SELECT c.*, w.wallet_ref, w.currency FROM wallet_cards c JOIN wallets w ON w.id = c.wallet_id "
                + "WHERE w.user_id = ? ORDER BY c.id DESC", userId).stream().map(this::view).toList();
    }

    public Map<String, Object> request(long userId, String walletRef, String currency, String scheme, String form, String nameOnCard,
                                       String deliveryAddress, String by) {
        return request(userId, walletRef, currency, scheme, form, nameOnCard, null, deliveryAddress, by);
    }

    public Map<String, Object> request(long userId, String walletRef, String currency, String scheme, String form, String nameOnCard,
                                       String nickname, String deliveryAddress, String by) {
        String s = upper(scheme), f = upper(form);
        if (!SCHEMES.contains(s)) throw new IllegalArgumentException("Choose VISA or ZimSwitch");
        if (!FORMS.contains(f)) throw new IllegalArgumentException("Choose a virtual or a physical card");
        WalletService.Wallet w = (walletRef != null && !walletRef.isBlank() ? wallets.byRef(walletRef.trim()) : wallets.forUser(userId, upper(currency)))
                .filter(x -> x.userId() == userId).orElseThrow(() -> new IllegalArgumentException("Open the wallet first"));
        if (!"ACTIVE".equals(w.status())) throw new IllegalStateException("This wallet is " + w.status().toLowerCase());
        if ("ZIMSWITCH".equals(s) && !"ZWG".equals(w.currency())) throw new IllegalArgumentException("ZimSwitch cards are linked to a ZWG wallet");
        String name = nameOnCard == null ? "" : nameOnCard.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z .'-]", "").replaceAll("\\s+", " ");
        if (name.length() > 26) name = name.substring(0, 26).trim();
        String nick = nickname == null || nickname.isBlank() ? null : nickname.trim();
        if (nick != null && nick.length() > 40) throw new IllegalArgumentException("Keep the nickname to 40 characters");
        if (name.isEmpty() || name.length() > 26 || !name.matches("[A-Z .'-]+")) throw new IllegalArgumentException("Enter the name for the card (letters only, up to 26)");
        String address = deliveryAddress == null ? null : deliveryAddress.trim();
        if ("PHYSICAL".equals(f) && (address == null || address.length() < 5)) throw new IllegalArgumentException("Enter the delivery address");
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM wallet_cards WHERE wallet_id = ? AND scheme = ? AND form = ? AND status IN ('REQUESTED','ACTIVE','FROZEN')",
                Integer.class, w.id(), s, f);
        if (open != null && open > 0) throw new IllegalStateException("You already have a " + f.toLowerCase() + " " + label(s) + " card on this wallet");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String ref = null;
        for (int i = 0; i < 10 && ref == null; i++) {
            String candidate = "C" + String.format("%011d", Math.floorMod(RANDOM.nextLong(), 100_000_000_000L));
            try {
                jdbc.update("INSERT INTO wallet_cards (card_ref, wallet_id, scheme, form, status, name_on_card, nickname, delivery_address, created_by, created_at, updated_at) "
                        + "VALUES (?,?,?,?, 'REQUESTED', ?,?,?,?,?,?)", candidate, w.id(), s, f, name, nick, "PHYSICAL".equals(f) ? address : null, by, now, now);
                ref = candidate;
            } catch (DuplicateKeyException e) {
                // try another reference
            }
        }
        if (ref == null) throw new IllegalStateException("Please try again");
        audit.record(by, "CARD_REQUESTED", "card:" + ref, s + " " + f + " on " + w.ref());
        CardIssuer issuer = issuer(s);
        if (issuer.connected()) {
            try {
                apply(ref, issuer.issue(row(ref)));
            } catch (RuntimeException e) {
                jdbc.update("UPDATE wallet_cards SET status_note = ?, updated_at = ? WHERE card_ref = ?", "Issuer: " + trim(e.getMessage()), now, ref);
            }
        } else {
            jdbc.update("UPDATE wallet_cards SET status_note = ? WHERE card_ref = ?", "Waiting for the card issuer", ref);
        }
        return view(row(ref));
    }

    /** Customer actions on their own card: FREEZE, UNFREEZE, CANCEL. */
    public Map<String, Object> customerAction(long userId, String cardRef, String action, String by) {
        Map<String, Object> c = row(cardRef);
        if (((Number) c.get("user_id")).longValue() != userId) throw new IllegalArgumentException("No such card");
        String a = switch (upper(action)) { case "BLOCK" -> "FREEZE"; case "UNBLOCK" -> "UNFREEZE"; default -> upper(action); };
        String status = (String) c.get("status");
        String target = switch (a) {
            case "FREEZE" -> { if (!"ACTIVE".equals(status)) throw new IllegalStateException("Only an active card can be blocked"); yield "FROZEN"; }
            case "UNFREEZE" -> { if (!"FROZEN".equals(status)) throw new IllegalStateException("This card is not blocked"); yield "ACTIVE"; }
            case "CANCEL" -> { if (!OPEN.contains(status)) throw new IllegalStateException("This card is already " + status.toLowerCase()); yield "CANCELLED"; }
            default -> throw new IllegalArgumentException("Choose freeze, unfreeze or cancel");
        };
        return change(c, target, null, by);
    }

    /** Staff: block, refuse, cancel, freeze or reactivate a card, with a note. */
    public Map<String, Object> staffStatus(String cardRef, String status, String note, String by) {
        Map<String, Object> c = row(cardRef);
        String target = upper(status);
        if (!Set.of("ACTIVE", "FROZEN", "BLOCKED", "CANCELLED", "REFUSED").contains(target)) throw new IllegalArgumentException("Unknown status");
        String current = (String) c.get("status");
        if (Set.of("CANCELLED", "REFUSED").contains(current)) throw new IllegalStateException("This card is " + current.toLowerCase());
        if ("REFUSED".equals(target) && !"REQUESTED".equals(current)) throw new IllegalStateException("Only a request can be refused");
        if (Set.of("ACTIVE", "FROZEN").contains(target) && "REQUESTED".equals(current)) throw new IllegalStateException("The card has not been issued yet");
        return change(c, target, note, by);
    }

    private Map<String, Object> change(Map<String, Object> c, String target, String note, String by) {
        String ref = (String) c.get("card_ref");
        String scheme = (String) c.get("scheme");
        boolean issued = c.get("issuer_ref") != null;
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        if (issued && !"REFUSED".equals(target)) {
            CardIssuer issuer = issuer(scheme);
            if (!issuer.connected()) throw new IllegalStateException(label(scheme) + " cards cannot be changed at the moment");
            apply(ref, issuer.setStatus(c, target));
        }
        jdbc.update("UPDATE wallet_cards SET status = ?, status_note = COALESCE(?, status_note), updated_at = ? WHERE card_ref = ?", target, note, now, ref);
        audit.record(by, "CARD_" + target, "card:" + ref, note == null ? "" : note);
        return view(row(ref));
    }

    /** Pull the issuer's view of the card (status, masked number, expiry). */
    public Map<String, Object> refresh(String cardRef) {
        Map<String, Object> c = row(cardRef);
        if (c.get("issuer_ref") != null) issuer((String) c.get("scheme")).fetch(c).ifPresent(i -> apply(cardRef, i));
        return view(row(cardRef));
    }

    /** The full number, expiry and CVV from the issuer, for the holder's own active card. */
    public Map<String, Object> reveal(long userId, String cardRef) {
        Map<String, Object> c = row(cardRef);
        if (((Number) c.get("user_id")).longValue() != userId) throw new IllegalArgumentException("No such card");
        if (c.get("issuer_ref") == null) throw new IllegalStateException("Card details are shown once the card is issued");
        CardIssuer issuer = issuer((String) c.get("scheme"));
        if (!issuer.connected()) throw new IllegalStateException("Card details are not available at the moment");
        CardIssuer.Sensitive s = issuer.reveal(c);
        audit.record("customer:" + c.get("phone"), "CARD_DETAILS_VIEWED", "card:" + cardRef, "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardRef", cardRef);
        out.put("pan", s.pan());
        out.put("expiry", s.expiry());
        out.put("cvv", s.cvv());
        return out;
    }

    public Map<String, Object> refreshFor(long userId, String cardRef) {
        Map<String, Object> c = row(cardRef);
        if (((Number) c.get("user_id")).longValue() != userId) throw new IllegalArgumentException("No such card");
        return refresh(cardRef);
    }

    private void apply(String ref, CardIssuer.Issued i) {
        if (i == null) return;
        String status = i.status() != null && Set.of("REQUESTED", "ACTIVE", "FROZEN", "BLOCKED", "CANCELLED", "REFUSED").contains(i.status()) ? i.status() : null;
        jdbc.update("UPDATE wallet_cards SET issuer_ref = COALESCE(?, issuer_ref), status = COALESCE(?, status), masked_pan = COALESCE(?, masked_pan), "
                        + "last4 = COALESCE(?, last4), expiry = COALESCE(?, expiry), status_note = COALESCE(?, status_note), updated_at = ? WHERE card_ref = ?",
                i.issuerRef(), status, i.maskedPan(), i.last4(), i.expiry(), i.note(), Timestamp.valueOf(LocalDateTime.now()), ref);
    }

    public List<Map<String, Object>> search(String q, String status, String scheme) {
        StringBuilder sql = new StringBuilder("SELECT c.*, w.wallet_ref, w.currency, w.phone, u.full_name AS holder FROM wallet_cards c JOIN wallets w ON w.id = c.wallet_id "
                + "LEFT JOIN users u ON u.id = w.user_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) { sql.append(" AND c.status = ?"); args.add(upper(status)); }
        if (scheme != null && !scheme.isBlank()) { sql.append(" AND c.scheme = ?"); args.add(upper(scheme)); }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (c.card_ref LIKE ? OR w.wallet_ref LIKE ? OR w.phone LIKE ? OR u.full_name LIKE ? OR c.last4 = ?)");
            String like = "%" + q.trim() + "%";
            args.addAll(List.of(like, like, like, like, q.trim()));
        }
        sql.append(" ORDER BY c.id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream().map(this::view).toList();
    }

    Map<String, Object> row(String ref) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT c.*, w.wallet_ref, w.currency, w.user_id, w.phone FROM wallet_cards c JOIN wallets w ON w.id = c.wallet_id WHERE c.card_ref = ?",
                ref == null ? "" : ref.trim());
        if (r.isEmpty()) throw new IllegalArgumentException("No such card");
        return r.get(0);
    }

    Map<String, Object> view(Map<String, Object> c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardRef", c.get("card_ref"));
        out.put("walletRef", c.get("wallet_ref"));
        out.put("currency", c.get("currency"));
        out.put("scheme", c.get("scheme"));
        out.put("form", c.get("form"));
        out.put("status", c.get("status"));
        out.put("maskedPan", c.get("masked_pan"));
        out.put("last4", c.get("last4"));
        out.put("expiry", c.get("expiry"));
        out.put("nameOnCard", c.get("name_on_card"));
        out.put("nickname", c.get("nickname"));
        out.put("deliveryAddress", c.get("delivery_address"));
        out.put("note", c.get("status_note"));
        out.put("issuerConnected", issuer((String) c.get("scheme")).connected());
        out.put("createdAt", c.get("created_at"));
        out.put("updatedAt", c.get("updated_at"));
        if (c.containsKey("holder")) out.put("holder", c.get("holder"));
        if (c.containsKey("phone")) out.put("phone", c.get("phone"));
        return out;
    }

    static String label(String scheme) {
        return "ZIMSWITCH".equals(scheme) ? "ZimSwitch" : scheme;
    }

    static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    static String trim(String s) {
        return s == null ? "" : s.length() > 280 ? s.substring(0, 280) : s;
    }
}
