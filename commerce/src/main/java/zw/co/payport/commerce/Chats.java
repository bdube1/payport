package zw.co.payport.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat with the seller (owner, 31 Oct 2026): a customer asks a store a question before ordering or paying, on the
 * website, in the app, on WhatsApp or on USSD; the seller answers in the merchant portal. The answer reaches the
 * customer where they asked (the page and the app show it; WhatsApp and USSD customers get it by WhatsApp or SMS).
 *
 * Messages, names and phone numbers are stored encrypted (Sealed). Each customer message is sorted into a topic so
 * merchants see what customers usually ask, and messages asking to be paid outside Payport, or abusive ones, are
 * flagged. Either side can report the other: the report goes to Payport customer care as a complaint.
 */
@Service
public class Chats {

    private static final Logger log = LoggerFactory.getLogger(Chats.class);
    public static final String COOKIE = "pp_chat";
    static final int MAX_TEXT = 1000;
    static final List<String> REASONS = List.of("RUDE", "SCAM", "OFF_PLATFORM", "NOT_DELIVERED", "FAKE", "SPAM", "OTHER");
    public static final Map<String, String> REASON_NAMES = new LinkedHashMap<>();

    static {
        REASON_NAMES.put("RUDE", "Rude or abusive");
        REASON_NAMES.put("SCAM", "Looks like a scam");
        REASON_NAMES.put("OFF_PLATFORM", "Asked to pay outside Payport");
        REASON_NAMES.put("NOT_DELIVERED", "Paid but not delivered");
        REASON_NAMES.put("FAKE", "Fake or misleading items");
        REASON_NAMES.put("SPAM", "Spam");
        REASON_NAMES.put("OTHER", "Something else");
    }

    private final JdbcTemplate jdbc;
    private final Sealed sealed;
    private final Ai ai;
    private final Psp psp;
    private final ExecutorService texts = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chat-texts");
        t.setDaemon(true);
        return t;
    });

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    ChatFiles files;

    public Chats(JdbcTemplate jdbc, Sealed sealed, Ai ai, Psp psp) {
        this.jdbc = jdbc;
        this.sealed = sealed;
        this.ai = ai;
        this.psp = psp;
    }

    /** Message text: line breaks kept, other control characters removed, 1 to 1000 characters. */
    static String body(Object o) {
        String s = o == null ? "" : String.valueOf(o).replace("\r\n", "\n").replaceAll("[\\u0000-\\u0009\\u000B-\\u001F\\u007F]", " ").trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Type a message");
        if (s.length() > MAX_TEXT) throw new IllegalArgumentException("A message is at most " + MAX_TEXT + " characters");
        return s;
    }

    static String phone9(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        if (d.length() < 9) throw new IllegalArgumentException("Invalid phone number");
        return d.substring(d.length() - 9);
    }

    // ─── finding or starting a conversation ──────────────────────────────────

    public record Who(Long shopperId, String token, String phone, String name, String channel) {}

    /** The customer's conversation with this store, if there is one. */
    public Optional<Map<String, Object>> find(long storeId, Who who) {
        List<Map<String, Object>> r;
        if (who.shopperId() != null) r = jdbc.queryForList("SELECT * FROM chats WHERE store_id = ? AND shopper_id = ? ORDER BY id DESC LIMIT 1", storeId, who.shopperId());
        else if (who.phone() != null) r = jdbc.queryForList("SELECT * FROM chats WHERE store_id = ? AND phone_hash = ? AND shopper_id IS NULL ORDER BY id DESC LIMIT 1",
                storeId, sealed.lookup(phone9(who.phone())));
        else if (who.token() != null && !who.token().isBlank()) r = jdbc.queryForList("SELECT * FROM chats WHERE store_id = ? AND token_hash = ? ORDER BY id DESC LIMIT 1",
                storeId, Shop.sha256(who.token()));
        else return Optional.empty();
        return r.stream().findFirst();
    }

    /** A customer's message to a store; starts the conversation when there is none. Returns the conversation id. */
    @Transactional
    public long customerSays(Map<String, Object> store, Who who, String text, Long productId) {
        String b = body(text);
        long storeId = ((Number) store.get("id")).longValue(), merchantId = ((Number) store.get("merchant_id")).longValue();
        Timestamp now = Shop.now();
        Map<String, Object> c = find(storeId, who).orElse(null);
        long id;
        if (c == null) {
            if (who.shopperId() == null && who.phone() == null && (who.token() == null || who.token().isBlank())) throw new IllegalStateException("Please try again");
            Integer recent = jdbc.queryForObject("SELECT COUNT(*) FROM chats WHERE store_id = ? AND created_at >= ?", Integer.class, storeId,
                    Timestamp.valueOf(LocalDateTime.now().minusHours(1)));
            if (recent != null && recent >= 200) throw new IllegalStateException("This store is receiving many messages. Please try again later.");
            String name = who.name() == null || who.name().isBlank() ? null : Shop.clip(who.name().trim(), 60);
            Object[] args = {storeId, merchantId, who.channel(), who.shopperId(), who.token() == null ? null : Shop.sha256(who.token()),
                    who.phone() == null ? null : sealed.lookup(phone9(who.phone())), who.phone() == null ? null : sealed.seal(who.phone()),
                    name == null ? null : sealed.seal(name), productId, now, now};
            org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("INSERT INTO chats (store_id, merchant_id, channel, shopper_id, token_hash, phone_hash, phone_enc, name_enc, product_id, last_at, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                return ps;
            }, keys);
            id = keys.getKey().longValue();
        } else {
            id = ((Number) c.get("id")).longValue();
            if (Shop.bool(c.get("blocked"))) throw new IllegalStateException("This store is not taking messages from you.");
            Integer lastMinute = jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE chat_id = ? AND sender = 'CUSTOMER' AND created_at >= ?", Integer.class, id,
                    Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
            if (lastMinute != null && lastMinute >= 10) throw new IllegalStateException("Please wait a moment before sending more messages.");
            if (productId != null) jdbc.update("UPDATE chats SET product_id = ? WHERE id = ?", productId, id);
            // the seller's answers go back where the customer last wrote from
            if (who.phone() != null) jdbc.update("UPDATE chats SET channel = ? WHERE id = ?", who.channel(), id);
            if (who.phone() != null && c.get("phone_enc") == null)
                jdbc.update("UPDATE chats SET phone_enc = ?, phone_hash = ? WHERE id = ?", sealed.seal(who.phone()), sealed.lookup(phone9(who.phone())), id);
        }
        jdbc.update("INSERT INTO chat_messages (chat_id, sender, body_enc, topic, flag, created_at) VALUES (?,?,?,?,?,?)",
                id, "CUSTOMER", sealed.seal(b), ai.topic(b), ai.flag(b), now);
        jdbc.update("UPDATE chats SET unread_merchant = unread_merchant + 1, last_at = ? WHERE id = ?", now, id);
        alertSeller(id, merchantId, store);
        return id;
    }

    /** The customer's conversation by id (only their own). */
    public Optional<Map<String, Object>> ofShopper(long chatId, long shopperId) {
        return jdbc.queryForList("SELECT * FROM chats WHERE id = ? AND shopper_id = ?", chatId, shopperId).stream().findFirst();
    }

    /** A customer sends a file or a contact in an existing conversation. */
    @Transactional
    public void customerSendsExtra(long chatId, String kind, Long fileId, String text) {
        Map<String, Object> c = jdbc.queryForMap("SELECT * FROM chats WHERE id = ?", chatId);
        if (Shop.bool(c.get("blocked"))) throw new IllegalStateException("This store is not taking messages from you.");
        Integer lastMinute = jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE chat_id = ? AND sender = 'CUSTOMER' AND created_at >= ?", Integer.class, chatId,
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
        if (lastMinute != null && lastMinute >= 10) throw new IllegalStateException("Please wait a moment before sending more messages.");
        Timestamp now = Shop.now();
        jdbc.update("INSERT INTO chat_messages (chat_id, sender, body_enc, topic, flag, kind, file_id, created_at) VALUES (?,?,?,?,?,?,?,?)",
                chatId, "CUSTOMER", sealed.seal(text), null, null, kind, fileId, now);
        jdbc.update("UPDATE chats SET unread_merchant = unread_merchant + 1, last_at = ? WHERE id = ?", now, chatId);
        Map<String, Object> store = jdbc.queryForMap("SELECT * FROM stores WHERE id = ?", c.get("store_id"));
        alertSeller(chatId, ((Number) c.get("merchant_id")).longValue(), store);
    }

    /** A customer's text in an existing conversation (the Messages page). */
    public void customerSaysIn(long chatId, String text) {
        Map<String, Object> c = jdbc.queryForMap("SELECT * FROM chats WHERE id = ?", chatId);
        Map<String, Object> store = jdbc.queryForMap("SELECT * FROM stores WHERE id = ?", c.get("store_id"));
        Long shopper = c.get("shopper_id") == null ? null : ((Number) c.get("shopper_id")).longValue();
        customerSays(store, new Who(shopper, null, null, null, "WEB"), text, null);
    }

    /** The seller sends a file or a contact. */
    @Transactional
    public Map<String, Object> sellerSendsExtra(long merchantId, long chatId, String kind, Long fileId, String text) {
        Map<String, Object> c = mine(merchantId, chatId);
        Timestamp now = Shop.now();
        jdbc.update("INSERT INTO chat_messages (chat_id, sender, body_enc, topic, flag, kind, file_id, created_at) VALUES (?,?,?,?,?,?,?,?)",
                chatId, "SELLER", sealed.seal(text), null, null, kind, fileId, now);
        jdbc.update("UPDATE chats SET unread_customer = unread_customer + 1, unread_merchant = 0, last_at = ? WHERE id = ?", now, chatId);
        forwardToPhone(c, merchantId, chatId, PREVIEW.getOrDefault(kind, "") + ("CONTACT".equals(kind) ? ": " + text.replace("\n", " ") : " (open Messages in the Payport app or store to see it)"));
        return thread(merchantId, chatId);
    }

    /** WhatsApp and USSD customers get the seller's messages where they asked. */
    private void forwardToPhone(Map<String, Object> c, long merchantId, long chatId, String b) {
        String channel = (String) c.get("channel");
        String phone = sealed.open((String) c.get("phone_enc"));
        if (phone == null && c.get("shopper_id") != null)
            phone = jdbc.queryForList("SELECT phone FROM shoppers WHERE id = ?", String.class, c.get("shopper_id")).stream().findFirst().orElse(null);
        if (phone == null) return;
        boolean byPhone = "WHATSAPP".equals(channel) || "USSD".equals(channel);
        String store = jdbc.queryForList("SELECT name FROM stores WHERE id = ?", String.class, c.get("store_id")).stream().findFirst().orElse("The store");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", merchantId);
        m.put("to", byPhone ? "CHAT_CUSTOMER" : "PUSH");     // app and website customers get a push on their phone
        m.put("channel", channel);
        m.put("phone", phone);
        m.put("text", Shop.clip(store + ": " + b, 300));
        m.put("reference", "CHAT-" + chatId);
        texts.submit(() -> {
            try {
                psp.message(m);
            } catch (RuntimeException e) {
                log.info("[COMMERCE] chat message {} not delivered: {}", chatId, e.getMessage());
            }
        });
    }

    /** Tells the seller by text that a customer is waiting (at most once an hour per conversation). */
    private void alertSeller(long chatId, long merchantId, Map<String, Object> store) {
        int n = jdbc.update("UPDATE chats SET notified_at = ? WHERE id = ? AND (notified_at IS NULL OR notified_at < ?)", Shop.now(), chatId,
                Timestamp.valueOf(LocalDateTime.now().minusHours(1)));
        if (n == 0) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("to", "MERCHANT");
        body.put("text", "Payport Store: a customer sent " + store.get("name") + " a message. Reply in the merchant portal under Store > Chats.");
        body.put("reference", "CHAT-" + chatId);
        texts.submit(() -> {
            try {
                psp.message(body);
            } catch (RuntimeException e) {
                log.info("[COMMERCE] chat alert not sent: {}", e.getMessage());
            }
        });
    }

    /** The messages of a conversation, oldest first, after the given message id. */
    public List<Map<String, Object>> messages(long chatId, long after) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, sender, body_enc, topic, flag, kind, file_id, created_at FROM chat_messages WHERE chat_id = ? AND id > ? ORDER BY id LIMIT 300", chatId, after)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", ((Number) r.get("id")).longValue());
            v.put("from", "SELLER".equals(r.get("sender")) ? "seller" : "customer");
            String t = sealed.open((String) r.get("body_enc"));
            String kind = r.get("kind") == null ? "TEXT" : (String) r.get("kind");
            v.put("kind", kind);
            if ("CONTACT".equals(kind) && t != null) {
                String[] nc = t.split("\n", 2);
                v.put("contact", Map.of("name", nc[0], "phone", nc.length > 1 ? nc[1] : ""));
                t = nc[0];
            }
            if (r.get("file_id") != null && files != null) files.describe(((Number) r.get("file_id")).longValue()).ifPresent(f -> v.put("file", f));
            v.put("text", t == null ? "(this message cannot be shown)" : t);
            v.put("time", Shop.time(r.get("created_at")).toString().replace('T', ' ').substring(0, 16));
            v.put("topic", r.get("topic"));
            v.put("flag", r.get("flag"));
            out.add(v);
        }
        return out;
    }

    /** The customer opened the conversation: the seller's messages are read. */
    public void customerRead(long chatId) {
        jdbc.update("UPDATE chats SET unread_customer = 0 WHERE id = ?", chatId);
    }

    // ─── the customer's messages page (all their conversations, kept for the record) ─

    /** Every conversation of a signed-in store user, newest first. Messages are never deleted. */
    public List<Map<String, Object>> forShopper(long shopperId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT c.*, s.name AS store_name, s.slug AS store_slug FROM chats c JOIN stores s ON s.id = c.store_id "
                + "WHERE c.shopper_id = ? ORDER BY c.last_at DESC LIMIT 200", shopperId)) {
            Map<String, Object> v = new LinkedHashMap<>();
            long id = ((Number) c.get("id")).longValue();
            v.put("id", id);
            v.put("store", c.get("store_name"));
            v.put("slug", c.get("store_slug"));
            v.put("url", "/messages?c=" + id);
            v.put("unread", ((Number) c.get("unread_customer")).intValue());
            v.put("time", Shop.time(c.get("last_at")).toString().replace('T', ' ').substring(0, 16));
            v.put("avatar", files == null ? null : files.avatar("MERCHANT", ((Number) c.get("merchant_id")).longValue()));
            v.put("blocked", Shop.bool(c.get("blocked")));
            v.put("preview", preview(id));
            out.add(v);
        }
        return out;
    }

    /** Every conversation of an app customer (found by their phone), newest first. */
    public List<Map<String, Object>> forPhone(String phone) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT c.*, s.name AS store_name, s.slug AS store_slug FROM chats c JOIN stores s ON s.id = c.store_id "
                + "WHERE c.phone_hash = ? AND c.shopper_id IS NULL ORDER BY c.last_at DESC LIMIT 200", sealed.lookup(phone9(phone)))) {
            Map<String, Object> v = new LinkedHashMap<>();
            long id = ((Number) c.get("id")).longValue();
            v.put("store", c.get("store_name"));
            v.put("slug", c.get("store_slug"));
            v.put("unread", ((Number) c.get("unread_customer")).intValue());
            v.put("time", Shop.time(c.get("last_at")).toString().replace('T', ' ').substring(0, 16));
            v.put("preview", preview(id));
            out.add(v);
        }
        return out;
    }

    /** Messages from sellers the store user has not read yet (the bell). */
    public int unreadForShopper(long shopperId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(unread_customer), 0) FROM chats WHERE shopper_id = ?", Integer.class, shopperId);
        return n == null ? 0 : n;
    }

    // ─── the seller's side (merchant portal) ─────────────────────────────────

    private Map<String, Object> mine(long merchantId, long chatId) {
        return jdbc.queryForList("SELECT * FROM chats WHERE id = ? AND merchant_id = ?", chatId, merchantId).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No such conversation"));
    }

    static final Map<String, String> PREVIEW = Map.of("IMAGE", "📷 Photo", "VIDEO", "🎥 Video", "AUDIO", "🎤 Audio", "DOCUMENT", "📄 Document", "CONTACT", "👤 Contact");

    /** The last message, for the conversation list. */
    String preview(long chatId) {
        List<Map<String, Object>> last = jdbc.queryForList("SELECT sender, body_enc, kind FROM chat_messages WHERE chat_id = ? ORDER BY id DESC LIMIT 1", chatId);
        if (last.isEmpty()) return "";
        String kind = last.get(0).get("kind") == null ? "TEXT" : (String) last.get(0).get("kind");
        String t = PREVIEW.containsKey(kind) ? PREVIEW.get(kind) : sealed.open((String) last.get(0).get("body_enc"));
        return t == null ? "" : Shop.clip(t, 90);
    }

    static String mask(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        return d.length() < 6 ? "***" : "+" + d.substring(0, Math.min(3, d.length() - 4)) + " *** " + d.substring(d.length() - 3);
    }

    Map<String, Object> summary(Map<String, Object> c) {
        Map<String, Object> v = new LinkedHashMap<>();
        long id = ((Number) c.get("id")).longValue();
        v.put("id", id);
        v.put("channel", c.get("channel"));
        String name = sealed.open((String) c.get("name_enc"));
        if (name == null && c.get("shopper_id") != null)
            name = jdbc.queryForList("SELECT name FROM shoppers WHERE id = ?", String.class, c.get("shopper_id")).stream().findFirst().orElse(null);
        v.put("customer", name == null ? "Customer" : name);
        v.put("phone", mask(sealed.open((String) c.get("phone_enc"))));
        v.put("unread", ((Number) c.get("unread_merchant")).intValue());
        v.put("blocked", Shop.bool(c.get("blocked")));
        v.put("lastAt", c.get("last_at"));
        v.put("product", c.get("product_id") == null ? null
                : jdbc.queryForList("SELECT name FROM products WHERE id = ?", String.class, c.get("product_id")).stream().findFirst().orElse(null));
        v.put("preview", preview(id));
        v.put("avatar", files == null || c.get("shopper_id") == null ? null : files.avatar("SHOPPER", ((Number) c.get("shopper_id")).longValue()));
        Integer flagged = jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE chat_id = ? AND flag IS NOT NULL", Integer.class, id);
        v.put("flagged", flagged != null && flagged > 0);
        return v;
    }

    public List<Map<String, Object>> list(long merchantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT * FROM chats WHERE merchant_id = ? ORDER BY last_at DESC LIMIT 200", merchantId)) out.add(summary(c));
        return out;
    }

    public Map<String, Object> thread(long merchantId, long chatId) {
        Map<String, Object> c = mine(merchantId, chatId);
        jdbc.update("UPDATE chats SET unread_merchant = 0 WHERE id = ?", chatId);
        Map<String, Object> v = summary(c);
        v.put("unread", 0);
        v.put("messages", messages(chatId, 0));
        return v;
    }

    @Transactional
    public Map<String, Object> reply(long merchantId, long chatId, Object text, String by) {
        Map<String, Object> c = mine(merchantId, chatId);
        String b = body(text);
        Timestamp now = Shop.now();
        jdbc.update("INSERT INTO chat_messages (chat_id, sender, body_enc, topic, flag, created_at) VALUES (?,?,?,?,?,?)",
                chatId, "SELLER", sealed.seal(b), null, ai.flag(b), now);
        jdbc.update("UPDATE chats SET unread_customer = unread_customer + 1, unread_merchant = 0, last_at = ? WHERE id = ?", now, chatId);
        forwardToPhone(c, merchantId, chatId, b);
        return thread(merchantId, chatId);
    }

    public Map<String, Object> block(long merchantId, long chatId, boolean blocked) {
        mine(merchantId, chatId);
        jdbc.update("UPDATE chats SET blocked = ? WHERE id = ?", blocked, chatId);
        return thread(merchantId, chatId);
    }

    public int unread(long merchantId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(SUM(unread_merchant), 0) FROM chats WHERE merchant_id = ?", Integer.class, merchantId);
        return n == null ? 0 : n;
    }

    /** What customers ask this store about (last 90 days), and how many messages were flagged. */
    public Map<String, Object> insights(long merchantId) {
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minusDays(90));
        List<Map<String, Object>> topics = new ArrayList<>();
        long total = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT m.topic, COUNT(*) AS n FROM chat_messages m JOIN chats c ON c.id = m.chat_id "
                + "WHERE c.merchant_id = ? AND m.sender = 'CUSTOMER' AND m.created_at >= ? GROUP BY m.topic ORDER BY n DESC", merchantId, since)) {
            long n = ((Number) r.get("n")).longValue();
            total += n;
            Map<String, Object> v = new LinkedHashMap<>();
            String t = r.get("topic") == null ? "OTHER" : (String) r.get("topic");
            v.put("topic", Ai.TOPIC_NAMES.getOrDefault(t, t));
            v.put("messages", n);
            topics.add(v);
        }
        for (Map<String, Object> t : topics) t.put("share", total == 0 ? 0 : Math.round(100.0 * ((Number) t.get("messages")).longValue() / total));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topics", topics);
        out.put("customerMessages", total);
        out.put("conversations", jdbc.queryForObject("SELECT COUNT(*) FROM chats WHERE merchant_id = ? AND created_at >= ?", Long.class, merchantId, since));
        out.put("flagged", jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages m JOIN chats c ON c.id = m.chat_id WHERE c.merchant_id = ? AND m.flag IS NOT NULL AND m.created_at >= ?",
                Long.class, merchantId, since));
        out.put("reports", jdbc.queryForObject("SELECT COUNT(*) FROM conduct_reports WHERE merchant_id = ? AND by_side = 'SELLER'", Long.class, merchantId));
        return out;
    }

    // ─── reports ─────────────────────────────────────────────────────────────

    /**
     * A report of a seller (by a customer) or of a customer (by the seller). Kept here (details encrypted) and sent to
     * Payport customer care as a complaint; the report's sender gets the complaint reference.
     */
    public String report(Map<String, Object> store, String bySide, Long chatId, String reason, String details, String reporterPhone) {
        String r = reason == null ? "" : reason.trim().toUpperCase(Locale.ROOT);
        if (!REASONS.contains(r)) throw new IllegalArgumentException("Choose a reason");
        String d = details == null || details.isBlank() ? null : body(details);
        long storeId = ((Number) store.get("id")).longValue(), merchantId = ((Number) store.get("merchant_id")).longValue();
        if ("CUSTOMER".equals(bySide)) {
            Integer today = jdbc.queryForObject("SELECT COUNT(*) FROM conduct_reports WHERE store_id = ? AND by_side = 'CUSTOMER' AND created_at >= ?", Integer.class, storeId,
                    Timestamp.valueOf(LocalDateTime.now().minusDays(1)));
            if (today != null && today >= 100) throw new IllegalStateException("Please try again later");
        }
        String ref = null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("about", "CUSTOMER".equals(bySide) ? "SELLER" : "CUSTOMER");
        body.put("reason", REASON_NAMES.get(r));
        body.put("store", store.get("name"));
        body.put("storeSlug", store.get("slug"));
        body.put("chatId", chatId);
        body.put("details", d);
        body.put("reporterPhone", "CUSTOMER".equals(bySide) ? reporterPhone : null);
        try {
            ref = psp.report(body).path("complaintRef").asText(null);
        } catch (RuntimeException e) {
            log.info("[COMMERCE] report not sent to customer care yet: {}", e.getMessage());
        }
        jdbc.update("INSERT INTO conduct_reports (store_id, merchant_id, by_side, chat_id, reason, details_enc, complaint_ref, created_at) VALUES (?,?,?,?,?,?,?,?)",
                storeId, merchantId, bySide, chatId, r, d == null ? null : sealed.seal(d), ref, Shop.now());
        return ref;
    }

    /** A seller reports the customer of one of its conversations. */
    public String sellerReports(long merchantId, long chatId, String reason, String details) {
        Map<String, Object> c = mine(merchantId, chatId);
        Map<String, Object> store = jdbc.queryForMap("SELECT * FROM stores WHERE id = ?", c.get("store_id"));
        return report(store, "SELLER", chatId, reason, details, null);
    }
}
