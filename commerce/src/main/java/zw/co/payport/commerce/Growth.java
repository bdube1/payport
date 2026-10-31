package zw.co.payport.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Store growth tools (owner, 31 Oct 2026): reviews from real buyers, delivery zones with their own fees, bookings for
 * services in the store's opening hours, and texts about orders (to the merchant for each new order, to the customer
 * when it is confirmed or ready).
 */
@Service
public class Growth {

    private static final Logger log = LoggerFactory.getLogger(Growth.class);
    /** Store hours and appointment times are local (Zimbabwe) times. */
    public static final ZoneId ZONE = ZoneId.of("Africa/Harare");
    static final int BOOKING_DAYS = 14, STEP_MIN = 30, LEAD_MIN = 60, DEFAULT_DURATION = 60;
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final Psp psp;
    private final Settings settings;
    private final ExecutorService texts = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "commerce-texts");
        t.setDaemon(true);
        return t;
    });

    public Growth(JdbcTemplate jdbc, Psp psp, Settings settings) {
        this.jdbc = jdbc;
        this.psp = psp;
        this.settings = settings;
    }

    static LocalDateTime localNow() {
        return LocalDateTime.now(ZONE).withNano(0);
    }

    long storeId(long merchantId) {
        return jdbc.queryForList("SELECT id FROM stores WHERE merchant_id = ?", Long.class, merchantId).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No store yet"));
    }

    // ─── reviews ─────────────────────────────────────────────────────────────

    /** A buyer's review: the order must be theirs (its link) and paid, and contain the product. One per product per order. */
    public void review(Map<String, Object> order, long productId, Object rating, String comment, String name) {
        if (!"PAID".equals(order.get("payment_status"))) throw new IllegalArgumentException("You can review once your order is paid");
        long orderId = ((Number) order.get("id")).longValue();
        Integer has = jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ? AND product_id = ?", Integer.class, orderId, productId);
        if (has == null || has == 0) throw new IllegalArgumentException("That item is not in this order");
        int r;
        try {
            r = Integer.parseInt(String.valueOf(rating));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Choose 1 to 5 stars");
        }
        if (r < 1 || r > 5) throw new IllegalArgumentException("Choose 1 to 5 stars");
        String c = Shop.text(comment, 600, false, "review");
        String n = Shop.text(name, 60, false, "name");
        if (n == null && order.get("customer_name") != null) n = String.valueOf(order.get("customer_name")).split("\\s+")[0];
        try {
            jdbc.update("INSERT INTO reviews (merchant_id, store_id, product_id, order_id, rating, comment, name, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    order.get("merchant_id"), order.get("store_id"), productId, orderId, r, c, n, Shop.now());
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("You have already reviewed this item");
        }
    }

    /** Which items of this order can still be reviewed (paid orders only). */
    public List<Map<String, Object>> reviewable(Map<String, Object> order) {
        if (!"PAID".equals(order.get("payment_status")) || order.get("store_id") == null) return List.of();
        return jdbc.queryForList("SELECT DISTINCT i.product_id AS productId, i.name FROM order_items i WHERE i.order_id = ? AND i.product_id IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.order_id = i.order_id AND r.product_id = i.product_id)", order.get("id"));
    }

    public List<Map<String, Object>> reviewsOf(long productId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT rating, comment, name, reply, created_at FROM reviews WHERE product_id = ? AND hidden = FALSE "
                + "ORDER BY id DESC LIMIT 20", productId)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("stars", stars(((Number) r.get("rating")).intValue()));
            v.put("comment", r.get("comment"));
            v.put("name", r.get("name") == null ? "A customer" : r.get("name"));
            v.put("reply", r.get("reply"));
            v.put("date", Shop.time(r.get("created_at")).toLocalDate().toString());
            out.add(v);
        }
        return out;
    }

    /** "★ 4.6 (12)" for a product, or null with no reviews. */
    public String ratingOf(long productId) {
        return rating(jdbc.queryForMap("SELECT AVG(rating) AS a, COUNT(*) AS n FROM reviews WHERE product_id = ? AND hidden = FALSE", productId));
    }

    public String storeRating(long storeId) {
        return rating(jdbc.queryForMap("SELECT AVG(rating) AS a, COUNT(*) AS n FROM reviews WHERE store_id = ? AND hidden = FALSE", storeId));
    }

    /** Ratings of every product in a store at once (for the product grid). */
    public Map<Long, String> productRatings(long storeId) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT product_id, AVG(rating) AS a, COUNT(*) AS n FROM reviews WHERE store_id = ? AND hidden = FALSE GROUP BY product_id", storeId))
            out.put(((Number) r.get("product_id")).longValue(), rating(r));
        return out;
    }

    static String rating(Map<String, Object> r) {
        long n = r.get("n") == null ? 0 : ((Number) r.get("n")).longValue();
        if (n == 0) return null;
        double a = ((Number) r.get("a")).doubleValue();
        return "★ " + String.format(Locale.ROOT, "%.1f", a) + " (" + n + ")";
    }

    static String stars(int n) {
        return "★★★★★".substring(0, n) + "☆☆☆☆☆".substring(0, 5 - n);
    }

    public List<Map<String, Object>> merchantReviews(long merchantId) {
        return jdbc.queryForList("SELECT r.id, r.rating, r.comment, r.name, r.reply, r.hidden, r.created_at AS createdAt, p.name AS product, o.order_ref AS orderRef "
                + "FROM reviews r JOIN products p ON p.id = r.product_id JOIN orders o ON o.id = r.order_id WHERE r.merchant_id = ? ORDER BY r.id DESC LIMIT 500", merchantId);
    }

    public List<Map<String, Object>> updateReview(long merchantId, long id, Map<String, Object> b) {
        Map<String, Object> r = jdbc.queryForList("SELECT * FROM reviews WHERE id = ? AND merchant_id = ?", id, merchantId).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No such review"));
        boolean hidden = b.containsKey("hidden") ? Shop.bool(b.get("hidden")) : Shop.bool(r.get("hidden"));
        String reply = b.containsKey("reply") ? Shop.text(b.get("reply"), 600, false, "reply") : (String) r.get("reply");
        jdbc.update("UPDATE reviews SET hidden = ?, reply = ? WHERE id = ?", hidden, reply, id);
        return merchantReviews(merchantId);
    }

    // ─── delivery zones ──────────────────────────────────────────────────────

    public List<Map<String, Object>> zones(long merchantId) {
        return jdbc.queryForList("SELECT id, name, fee_minor AS feeMinor, free_from_minor AS freeFromMinor, position FROM delivery_zones WHERE merchant_id = ? ORDER BY position, name", merchantId);
    }

    public List<Map<String, Object>> addZone(long merchantId, Map<String, Object> b) {
        String name = Shop.text(b.get("name"), 60, true, "area name");
        Object feeIn = b.get("feeMinor");
        long fee;
        try {
            fee = feeIn == null || String.valueOf(feeIn).isBlank() ? 0 : Long.parseLong(String.valueOf(feeIn));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter the delivery fee");
        }
        if (fee < 0 || fee > 100_000_000L) throw new IllegalArgumentException("Enter the delivery fee");
        Long free = Shop.optionalMoney(b.get("freeFromMinor"), "free delivery amount");
        long store = storeId(merchantId);
        Integer pos = jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) + 1 FROM delivery_zones WHERE store_id = ?", Integer.class, store);
        try {
            jdbc.update("INSERT INTO delivery_zones (merchant_id, store_id, name, fee_minor, free_from_minor, position) VALUES (?,?,?,?,?,?)", merchantId, store, name, fee, free, pos);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("You already have that area");
        }
        return zones(merchantId);
    }

    public void removeZone(long merchantId, long id) {
        if (jdbc.update("DELETE FROM delivery_zones WHERE id = ? AND merchant_id = ?", id, merchantId) == 0) throw new Shop.NotFound("No such area");
    }

    public List<Map<String, Object>> publicZones(long storeId) {
        return jdbc.queryForList("SELECT id, name, fee_minor, free_from_minor FROM delivery_zones WHERE store_id = ? ORDER BY position, name", storeId);
    }

    public Optional<Map<String, Object>> zone(long storeId, String id) {
        if (id == null || !id.matches("\\d{1,18}")) return Optional.empty();
        return jdbc.queryForList("SELECT id, name, fee_minor, free_from_minor FROM delivery_zones WHERE store_id = ? AND id = ?", storeId, Long.parseLong(id)).stream().findFirst();
    }

    static long zoneFee(Map<String, Object> z, long itemsMinor) {
        Object free = z.get("free_from_minor");
        if (free != null && itemsMinor >= ((Number) free).longValue()) return 0;
        return ((Number) z.get("fee_minor")).longValue();
    }

    // ─── opening hours and bookings ──────────────────────────────────────────

    public Map<String, Object> hours(long merchantId) {
        long store = storeId(merchantId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("staff", jdbc.queryForObject("SELECT booking_staff FROM stores WHERE id = ?", Integer.class, store));
        List<Map<String, Object>> days = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT weekday, opens, closes FROM store_hours WHERE store_id = ? ORDER BY weekday", store)) {
            days.add(Map.of("weekday", r.get("weekday"), "opens", hhmm(r.get("opens")), "closes", hhmm(r.get("closes"))));
        }
        v.put("days", days);
        return v;
    }

    /** Body: {staff: 2, days: [{weekday: 1 (Monday) .. 7, opens: "08:00", closes: "17:00"}]}; days not listed are closed. */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> setHours(long merchantId, Map<String, Object> b) {
        long store = storeId(merchantId);
        int staff;
        try {
            staff = b.get("staff") == null ? 1 : Integer.parseInt(String.valueOf(b.get("staff")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter how many people serve at once");
        }
        if (staff < 1 || staff > 50) throw new IllegalArgumentException("Between 1 and 50 people serve at once");
        List<int[]> rows = new ArrayList<>();
        List<LocalTime[]> times = new ArrayList<>();
        if (b.get("days") instanceof List<?> days) {
            for (Object o : days) {
                if (!(o instanceof Map<?, ?> d)) continue;
                int wd;
                LocalTime opens, closes;
                try {
                    wd = Integer.parseInt(String.valueOf(d.get("weekday")));
                    opens = LocalTime.parse(String.valueOf(d.get("opens")));
                    closes = LocalTime.parse(String.valueOf(d.get("closes")));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Enter opening hours as 08:00 to 17:00");
                }
                if (wd < 1 || wd > 7) throw new IllegalArgumentException("Choose the day");
                if (!closes.isAfter(opens)) throw new IllegalArgumentException("Closing time must be after opening time");
                rows.add(new int[]{wd});
                times.add(new LocalTime[]{opens, closes});
            }
        }
        jdbc.update("UPDATE stores SET booking_staff = ? WHERE id = ?", staff, store);
        jdbc.update("DELETE FROM store_hours WHERE store_id = ?", store);
        for (int i = 0; i < rows.size(); i++) {
            jdbc.update("INSERT INTO store_hours (store_id, weekday, opens, closes) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE opens = VALUES(opens), closes = VALUES(closes)",
                    store, rows.get(i)[0], java.sql.Time.valueOf(times.get(i)[0]), java.sql.Time.valueOf(times.get(i)[1]));
        }
        return hours(merchantId);
    }

    public boolean takesBookings(long storeId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM store_hours WHERE store_id = ?", Integer.class, storeId);
        return n != null && n > 0;
    }

    public record Slot(LocalDateTime start, String value, String label, String day) {}

    /** Free start times for an appointment of this length in the next two weeks, within the opening hours. */
    public List<Slot> slots(long storeId, int minutes) {
        Map<Integer, LocalTime[]> hours = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT weekday, opens, closes FROM store_hours WHERE store_id = ?", storeId))
            hours.put(((Number) r.get("weekday")).intValue(), new LocalTime[]{time(r.get("opens")), time(r.get("closes"))});
        if (hours.isEmpty()) return List.of();
        int staff = jdbc.queryForObject("SELECT booking_staff FROM stores WHERE id = ?", Integer.class, storeId);
        LocalDateTime now = localNow(), earliest = now.plusMinutes(LEAD_MIN);
        List<LocalDateTime[]> taken = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT starts_at, ends_at FROM bookings WHERE store_id = ? AND status IN ('HELD','CONFIRMED') AND ends_at > ? AND starts_at < ?",
                storeId, Timestamp.valueOf(now), Timestamp.valueOf(now.plusDays(BOOKING_DAYS + 1))))
            taken.add(new LocalDateTime[]{Shop.time(r.get("starts_at")), Shop.time(r.get("ends_at"))});
        List<Slot> out = new ArrayList<>();
        for (int d = 0; d < BOOKING_DAYS && out.size() < 300; d++) {
            LocalDate date = now.toLocalDate().plusDays(d);
            LocalTime[] h = hours.get(date.getDayOfWeek().getValue());
            if (h == null) continue;
            for (LocalDateTime t = date.atTime(h[0]); !t.plusMinutes(minutes).isAfter(date.atTime(h[1])); t = t.plusMinutes(STEP_MIN)) {
                if (t.isBefore(earliest)) continue;
                LocalDateTime s = t, e = t.plusMinutes(minutes);
                long busy = taken.stream().filter(x -> x[0].isBefore(e) && x[1].isAfter(s)).count();
                if (busy < staff) out.add(new Slot(t, t.toString(), t.format(WHEN), date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH))));
            }
        }
        return out;
    }

    /** The chosen time, checked to be one of the free times (nothing from the browser is trusted). */
    public Optional<LocalDateTime> validSlot(long storeId, int minutes, String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        return slots(storeId, minutes).stream().filter(s -> s.value().equals(value.trim())).map(Slot::start).findFirst();
    }

    /** Part of placing the order (same transaction): the appointment, held when the order reserves. */
    void pending(long orderId, long storeId, long merchantId, LocalDateTime start, int minutes) {
        jdbc.update("INSERT INTO bookings (order_id, store_id, merchant_id, starts_at, ends_at, status, created_at) VALUES (?,?,?,?,?, 'PENDING', ?)",
                orderId, storeId, merchantId, Timestamp.valueOf(start), Timestamp.valueOf(start.plusMinutes(minutes)), Shop.now());
    }

    boolean hasBooking(long orderId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE order_id = ?", Integer.class, orderId);
        return n != null && n > 0;
    }

    /**
     * Holds the order's appointment (inside the order's transaction, with the store row locked so two customers cannot
     * both take the last place at that time). Returns why not.
     */
    String hold(long orderId) {
        List<Map<String, Object>> b = jdbc.queryForList("SELECT * FROM bookings WHERE order_id = ?", orderId);
        if (b.isEmpty()) return null;
        Map<String, Object> bk = b.get(0);
        if ("HELD".equals(bk.get("status")) || "CONFIRMED".equals(bk.get("status"))) return null;
        long store = ((Number) bk.get("store_id")).longValue();
        Integer staff = jdbc.queryForObject("SELECT booking_staff FROM stores WHERE id = ? FOR UPDATE", Integer.class, store);
        LocalDateTime s = Shop.time(bk.get("starts_at")), e = Shop.time(bk.get("ends_at"));
        if (!s.isAfter(localNow())) return "That time has passed. Please choose another time.";
        Integer busy = jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE store_id = ? AND order_id <> ? AND status IN ('HELD','CONFIRMED') AND starts_at < ? AND ends_at > ?",
                Integer.class, store, orderId, Timestamp.valueOf(e), Timestamp.valueOf(s));
        if (busy != null && staff != null && busy >= staff) return "That time has just been taken. Please choose another time.";
        jdbc.update("UPDATE bookings SET status = 'HELD' WHERE id = ?", bk.get("id"));
        return null;
    }

    void confirm(long orderId) {
        jdbc.update("UPDATE bookings SET status = 'CONFIRMED' WHERE order_id = ? AND status IN ('PENDING','HELD','RELEASED')", orderId);
    }

    void release(long orderId) {
        jdbc.update("UPDATE bookings SET status = 'RELEASED' WHERE order_id = ? AND status IN ('HELD','CONFIRMED')", orderId);
    }

    public Optional<String> bookingOf(long orderId) {
        return jdbc.queryForList("SELECT starts_at FROM bookings WHERE order_id = ?", orderId).stream().findFirst()
                .map(r -> Shop.time(r.get("starts_at")).format(WHEN));
    }

    public List<Map<String, Object>> merchantBookings(long merchantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT b.starts_at, b.ends_at, b.status, o.order_ref, o.item_name, o.customer_name, o.customer_phone, o.payment_status "
                + "FROM bookings b JOIN orders o ON o.id = b.order_id WHERE b.merchant_id = ? AND b.status IN ('HELD','CONFIRMED') AND b.ends_at >= ? ORDER BY b.starts_at LIMIT 500",
                merchantId, Timestamp.valueOf(localNow().minusDays(1)))) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("startsAt", Shop.time(r.get("starts_at")).toString());
            v.put("endsAt", Shop.time(r.get("ends_at")).toString());
            v.put("when", Shop.time(r.get("starts_at")).format(WHEN));
            v.put("status", r.get("status"));
            v.put("orderRef", r.get("order_ref"));
            v.put("service", r.get("item_name"));
            v.put("customerName", r.get("customer_name"));
            v.put("customerPhone", r.get("customer_phone"));
            v.put("paymentStatus", r.get("payment_status"));
            out.add(v);
        }
        return out;
    }

    // ─── texts about orders ──────────────────────────────────────────────────

    /** The order is confirmed (paid, or placed to pay on delivery): a text to the merchant and to the customer. */
    public void orderConfirmed(String orderRef) {
        afterCommit(() -> {
            Map<String, Object> o = order(orderRef);
            if (o == null) return;
            Map<String, Object> st = store(o);
            if (st == null) return;
            long merchantId = ((Number) o.get("merchant_id")).longValue();
            String money = o.get("currency") + " " + Shop.major(((Number) o.get("amount_minor")).longValue());
            String when = bookingOf(((Number) o.get("id")).longValue()).map(w -> ", " + w).orElse("");
            String how = "CASH".equals(o.get("pay_method")) ? "pay on delivery" : "paid";
            String method = switch (String.valueOf(o.get("delivery_method"))) {
                case "DELIVERY" -> ", delivery";
                case "PICKUP" -> ", pickup";
                default -> "";
            };
            if (Shop.bool(st.get("alerts_enabled"))) {
                send(merchantId, "MERCHANT", null, "New order " + orderRef + ": " + o.get("item_name") + ", " + money + " (" + how + method + when + "). "
                        + (o.get("customer_name") == null ? "" : o.get("customer_name") + " ") + (o.get("customer_phone") == null ? "" : o.get("customer_phone")), orderRef);
            }
            if (Shop.bool(st.get("customer_sms")) && o.get("customer_phone") != null) {
                send(merchantId, "CUSTOMER", (String) o.get("customer_phone"), "Thank you! Your order " + orderRef + " at " + st.get("name") + " is confirmed"
                        + when + ". " + settings.publicUrl + "/o/" + orderRef + "?t=" + token(o), orderRef);
            }
        });
    }

    /** Ready to collect, or on its way: a text to the customer. */
    public void deliveryStep(String orderRef, String step) {
        if (!"READY".equals(step) && !"OUT".equals(step)) return;
        afterCommit(() -> {
            Map<String, Object> o = order(orderRef);
            if (o == null || o.get("customer_phone") == null) return;
            Map<String, Object> st = store(o);
            if (st == null || !Shop.bool(st.get("customer_sms"))) return;
            String what = "READY".equals(step) ? "is ready to collect" : "is on its way";
            String pay = "ON_DELIVERY".equals(o.get("payment_status")) ? " Please have " + o.get("currency") + " " + Shop.major(((Number) o.get("amount_minor")).longValue()) + " ready." : "";
            send(((Number) o.get("merchant_id")).longValue(), "CUSTOMER", (String) o.get("customer_phone"), "Your order " + orderRef + " from " + st.get("name") + " " + what + "." + pay, orderRef);
        });
    }

    private Map<String, Object> order(String ref) {
        return jdbc.queryForList("SELECT * FROM orders WHERE order_ref = ?", ref).stream().findFirst().orElse(null);
    }

    private Map<String, Object> store(Map<String, Object> o) {
        if (o.get("store_id") == null) return jdbc.queryForList("SELECT * FROM stores WHERE merchant_id = ?", o.get("merchant_id")).stream().findFirst().orElse(null);
        return jdbc.queryForList("SELECT * FROM stores WHERE id = ?", o.get("store_id")).stream().findFirst().orElse(null);
    }

    private String token(Map<String, Object> o) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(settings.tokenSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("view:" + o.get("idempotency_key")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void send(long merchantId, String to, String phone, String text, String ref) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("to", to);
        body.put("phone", phone);
        body.put("text", text.length() > 300 ? text.substring(0, 297) + "..." : text);
        body.put("reference", ref);
        try {
            psp.message(body);
        } catch (RuntimeException e) {
            log.info("[COMMERCE] text about {} not sent: {}", ref, e.getMessage());
        }
    }

    /** Texts go out after the order change is saved, off the customer's request. */
    private void afterCommit(Runnable r) {
        Runnable job = () -> texts.submit(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.info("[COMMERCE] order text failed: {}", e.getMessage());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    job.run();
                }
            });
        } else {
            job.run();
        }
    }

    static String hhmm(Object t) {
        return time(t).toString();
    }

    static LocalTime time(Object t) {
        if (t instanceof java.sql.Time x) return x.toLocalTime();
        if (t instanceof LocalTime x) return x;
        return LocalTime.parse(String.valueOf(t));
    }
}
