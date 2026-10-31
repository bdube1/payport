package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.Set;

/**
 * Orders and their payments. The PSP is the only authority on money: an order is PAID only when the PSP says so
 * (its event stream or its status), an unclear answer shows "being confirmed", and a refund stays REQUESTED until the
 * PSP says the customer has been paid back. Each try at paying is one PSP checkout with its own reference
 * (CO-XXXXXXXX-1, -2, ...): a double click or a retry of the same try reuses it, and a late payment on an earlier
 * try still marks the order paid.
 */
@Service
public class Payments {

    private static final Logger log = LoggerFactory.getLogger(Payments.class);
    /** Payment states that can still change. */
    static final Set<String> OPEN = Set.of("NOT_STARTED", "AWAITING_PAYMENT", "PENDING", "CONFIRMING", "FAILED", "EXPIRED");

    private final JdbcTemplate jdbc;
    private final Shop shop;
    private final Psp psp;
    private final Settings settings;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired Growth growth;

    /** An appointment to book with the order (services): its start (local time) and length. */
    public record Booking(LocalDateTime start, int minutes) {}

    public Payments(JdbcTemplate jdbc, Shop shop, Psp psp, Settings settings, org.springframework.transaction.PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.shop = shop;
        this.psp = psp;
        this.settings = settings;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    }

    /**
     * The customer's key to their order page, derived from the one-time key of the page they ordered from: the same
     * submit always gets the same order and the same key, and nobody can work it out without Commerce's secret.
     */
    String viewToken(String idempotencyKey) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(settings.tokenSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("view:" + idempotencyKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** One line of an order, at the price of the moment. */
    public record Item(Long productId, String name, long unitMinor, int quantity) {}

    public record NewOrder(long merchantId, Long storeId, Long linkId, Long productId, String itemName, int quantity, long amountMinor,
                           String currency, String customerName, String customerPhone, String note, String idempotencyKey,
                           List<Item> items, String deliveryMethod, long deliveryFeeMinor, String deliveryAddress) {
        /** A single payment (payment link, or one product): no delivery choice. */
        public NewOrder(long merchantId, Long storeId, Long linkId, Long productId, String itemName, int quantity, long amountMinor,
                        String currency, String customerName, String customerPhone, String note, String idempotencyKey) {
            this(merchantId, storeId, linkId, productId, itemName, quantity, amountMinor, currency, customerName, customerPhone, note, idempotencyKey,
                    List.of(), "NONE", 0, null);
        }
    }

    public record Placed(String orderRef, String viewToken, boolean existing) {}

    /** The order, once per idempotency key (a double submit gets the same order back). */
    @Transactional
    public Placed place(NewOrder o) {
        return place(o, null);
    }

    /** As above, with an appointment that is held together with the items (and released with them). */
    @Transactional
    public Placed place(NewOrder o, Booking booking) {
        if (o.idempotencyKey() == null || !o.idempotencyKey().matches("[A-Za-z0-9_-]{16,64}")) throw new IllegalArgumentException("Please reload the page and try again");
        List<Map<String, Object>> had = jdbc.queryForList("SELECT order_ref FROM orders WHERE idempotency_key = ?", o.idempotencyKey());
        String token = viewToken(o.idempotencyKey());
        if (!had.isEmpty()) return new Placed((String) had.get(0).get("order_ref"), token, true);
        for (int i = 0; i < 20; i++) {
            String ref = "CO-" + Shop.code(8);
            try {
                jdbc.update("INSERT INTO orders (order_ref, merchant_id, store_id, link_id, product_id, item_name, quantity, amount_minor, currency, customer_name, customer_phone, note, "
                                + "view_token_hash, idempotency_key, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        ref, o.merchantId(), o.storeId(), o.linkId(), o.productId(), Shop.clip(o.itemName(), 160), o.quantity(), o.amountMinor(), o.currency(),
                        o.customerName(), o.customerPhone(), o.note(), Shop.sha256(token), o.idempotencyKey(), Shop.now(), Shop.now());
                long id = jdbc.queryForObject("SELECT id FROM orders WHERE order_ref = ?", Long.class, ref);
                long itemsMinor = 0;
                for (Item it : o.items()) {
                    jdbc.update("INSERT INTO order_items (order_id, product_id, name, unit_minor, quantity) VALUES (?,?,?,?,?)",
                            id, it.productId(), Shop.clip(it.name(), 160), it.unitMinor(), it.quantity());
                    itemsMinor += it.unitMinor() * it.quantity();
                }
                jdbc.update("UPDATE orders SET items_minor = ?, delivery_method = ?, delivery_fee_minor = ?, delivery_address = ? WHERE id = ?",
                        o.items().isEmpty() ? null : itemsMinor, o.deliveryMethod() == null ? "NONE" : o.deliveryMethod(), o.deliveryFeeMinor(), o.deliveryAddress(), id);
                if (booking != null) growth.pending(id, o.storeId(), o.merchantId(), booking.start(), booking.minutes());
                String short_ = reserve(id);
                if (short_ != null) throw new IllegalArgumentException(short_);   // rolls the whole order back
                shop.event("order.created", o.merchantId(), ref, o.linkId() != null ? "link" : "store");
                return new Placed(ref, token, false);
            } catch (DuplicateKeyException e) {
                List<Map<String, Object>> raced = jdbc.queryForList("SELECT order_ref FROM orders WHERE idempotency_key = ?", o.idempotencyKey());
                if (!raced.isEmpty()) return new Placed((String) raced.get(0).get("order_ref"), token, true);
            }
        }
        throw new IllegalStateException("Please try again");
    }

    public Optional<Map<String, Object>> order(String ref) {
        return jdbc.queryForList("SELECT * FROM orders WHERE order_ref = ?", ref == null ? "" : ref).stream().findFirst();
    }

    /** After placing: the discount used and how the customer pays. Pay on delivery holds the items until the cash is recorded. */
    public void finish(String ref, boolean cash, String discountCode, long discountMinor) {
        finish(ref, cash, discountCode, discountMinor, null, null);
    }

    /** As above, with the customer's payment method choice and delivery area. */
    public void finish(String ref, boolean cash, String discountCode, long discountMinor, String payChoice, String zone) {
        jdbc.update("UPDATE orders SET pay_choice = ?, delivery_zone = ? WHERE order_ref = ?", payChoice, zone, ref);
        int n = jdbc.update("UPDATE orders SET pay_method = ?, discount_code = ?, discount_minor = ?, payment_status = CASE WHEN ? THEN 'ON_DELIVERY' ELSE payment_status END, "
                + "delivery_status = CASE WHEN ? AND delivery_method <> 'NONE' THEN 'PREPARING' ELSE delivery_status END, updated_at = ? WHERE order_ref = ? AND payment_status = 'NOT_STARTED'",
                cash ? "CASH" : "ONLINE", discountCode, discountMinor, cash, cash, Shop.now(), ref);
        if (n > 0 && cash) growth.orderConfirmed(ref);
    }

    /** The merchant received the cash for a pay-on-delivery order: it is paid (not through the PSP). */
    public Map<String, Object> cashReceived(long merchantId, String ref) {
        Map<String, Object> o = jdbc.queryForList("SELECT * FROM orders WHERE order_ref = ? AND merchant_id = ?", ref, merchantId).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No such order"));
        if (!"ON_DELIVERY".equals(o.get("payment_status"))) throw new IllegalStateException("Only a pay-on-delivery order waiting for cash can be marked paid");
        long id = ((Number) o.get("id")).longValue();
        jdbc.update("UPDATE orders SET payment_status = 'PAID', paid_at = ?, updated_at = ? WHERE id = ? AND payment_status = 'ON_DELIVERY'", Shop.now(), Shop.now(), id);
        sell(id);
        shop.event("order.paid", merchantId, ref, "cash " + o.get("amount_minor"));
        return merchantOrder(merchantId, ref);
    }

    /** Which channel the order came from (web unless a channel says otherwise). */
    public void channel(String ref, String channel) {
        jdbc.update("UPDATE orders SET channel = ? WHERE order_ref = ?", channel, ref);
    }

    /** The PSP checkout of the order's current payment try (what a channel pays). */
    public Long lastSessionId(String ref) {
        return jdbc.queryForList("SELECT a.psp_session_id FROM order_attempts a JOIN orders o ON o.id = a.order_id WHERE o.order_ref = ? ORDER BY a.id DESC LIMIT 1",
                Long.class, ref).stream().findFirst().orElse(null);
    }

    /** Recent orders placed with this phone number (the last nine digits), newest first. */
    public List<Map<String, Object>> ordersByPhone(String phone, int limit) {
        String d = phone == null ? "" : phone.replaceAll("\\D", "");
        if (d.length() < 9) return List.of();
        return jdbc.queryForList("SELECT * FROM orders WHERE store_id IS NOT NULL AND customer_phone LIKE ? ORDER BY id DESC LIMIT ?", "%" + d.substring(d.length() - 9), Math.min(limit, 50));
    }

    /** The customer's view of an order needs the token from their link (orders cannot be looked up by number alone). */
    public Optional<Map<String, Object>> orderForCustomer(String ref, String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return order(ref).filter(o -> java.security.MessageDigest.isEqual(Shop.sha256(token).getBytes(), String.valueOf(o.get("view_token_hash")).getBytes()));
    }

    public String orderUrl(String ref, String token) {
        return settings.publicUrl + "/o/" + ref + "?t=" + token;
    }

    /** The result of asking to pay: where to send the customer, or why not now. */
    public record Start(String checkoutUrl, String problem, boolean pspDown) {}

    /**
     * Sends the customer to the PSP's checkout. The current try is reused while it can still be paid; after a failed or
     * expired try a new one starts. If the PSP cannot be reached the order is kept and the customer can try again.
     */
    public Start pay(String ref, String token) {
        Map<String, Object> o = orderForCustomer(ref, token).orElseThrow(() -> new Shop.NotFound("No such order"));
        String ps = String.valueOf(o.get("payment_status"));
        if ("PAID".equals(ps)) return new Start(null, "This order is already paid.", false);
        if ("PENDING".equals(ps) || "CONFIRMING".equals(ps)) return new Start(null, "Your payment is being confirmed.", false);
        if ("ON_DELIVERY".equals(ps)) return new Start(null, "You pay for this order on delivery or collection.", false);
        if ("CANCELLED".equals(o.get("order_status"))) return new Start(null, "This order was cancelled.", false);
        long orderId = ((Number) o.get("id")).longValue(), merchantId = ((Number) o.get("merchant_id")).longValue();
        String gone = reserveAgain(orderId);
        if (gone != null) return new Start(null, "Sorry: " + gone, false);
        int attempt = ((Number) o.get("attempts")).intValue();
        boolean reuse = attempt > 0 && "AWAITING_PAYMENT".equals(ps);
        if (!reuse) attempt++;
        String reference = ref + "-" + attempt;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("reference", reference);
        body.put("orderRef", ref);
        if (o.get("pay_choice") != null) body.put("method", o.get("pay_choice"));
        body.put("amountMinor", ((Number) o.get("amount_minor")).longValue());
        body.put("currency", o.get("currency"));
        body.put("description", Shop.clip(o.get("item_name") + (((Number) o.get("quantity")).intValue() > 1 ? " x" + o.get("quantity") : ""), 200));
        body.put("successUrl", orderUrl(ref, token));
        body.put("cancelUrl", orderUrl(ref, token));
        JsonNode c;
        try {
            c = psp.checkout(body);
        } catch (Psp.Unavailable e) {
            log.warn("[COMMERCE] checkout for {} not started: {}", ref, e.getMessage());
            return new Start(null, "Payments are briefly unavailable. Your order " + ref + " is saved: please try again in a few minutes.", true);
        } catch (Psp.Refused e) {
            return new Start(null, e.getMessage(), false);
        }
        String url = c.path("checkoutUrl").asText("");
        if (!url.startsWith(settings.checkoutOrigin + "/")) {
            log.warn("[COMMERCE] checkout URL for {} is not on {}: refused", ref, settings.checkoutOrigin);
            return new Start(null, "Payments are briefly unavailable. Please try again in a few minutes.", true);
        }
        Timestamp now = Shop.now();
        jdbc.update("INSERT INTO order_attempts (order_id, merchant_id, psp_reference, psp_session_id, checkout_url, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE psp_session_id = VALUES(psp_session_id), checkout_url = VALUES(checkout_url), updated_at = VALUES(updated_at)",
                orderId, merchantId, reference, c.path("checkoutSessionId").asLong(), url, "AWAITING_PAYMENT", now, now);
        jdbc.update("UPDATE orders SET attempts = GREATEST(attempts, ?), payment_status = CASE WHEN payment_status IN ('NOT_STARTED','FAILED','EXPIRED','AWAITING_PAYMENT') "
                + "THEN 'AWAITING_PAYMENT' ELSE payment_status END, updated_at = ? WHERE id = ?", attempt, now, orderId);
        if (!reuse) shop.event("checkout.started", merchantId, ref, String.valueOf(attempt));
        apply(merchantId, reference, c);   // the PSP may already know more (a retry after a payment)
        return new Start(url, null, false);
    }

    // ─── what the PSP says ───────────────────────────────────────────────────

    /**
     * Applies the PSP's view of one checkout (from its status or an event). Only moves forward: PAID is final (a late or
     * repeated confirmation changes nothing), and a failure on one try never undoes a payment on another.
     */
    public void apply(long merchantId, String pspReference, JsonNode view) {
        tx.executeWithoutResult(status -> applyNow(merchantId, pspReference, view));
    }

    private void applyNow(long merchantId, String pspReference, JsonNode view) {
        List<Map<String, Object>> a = jdbc.queryForList("SELECT a.id, a.order_id, o.payment_status FROM order_attempts a JOIN orders o ON o.id = a.order_id "
                + "WHERE a.merchant_id = ? AND a.psp_reference = ? FOR UPDATE", merchantId, pspReference);
        if (a.isEmpty()) return;
        long attemptId = ((Number) a.get(0).get("id")).longValue(), orderId = ((Number) a.get(0).get("order_id")).longValue();
        String outcome = view.path("outcome").asText(""), status = view.path("status").asText("");
        String attemptStatus = switch (outcome) {
            case "PAID" -> "PAID";
            case "FAILED" -> "FAILED";
            case "NOT_PAID" -> "EXPIRED";
            case "UNCERTAIN" -> "CONFIRMING";
            case "PENDING" -> "PENDING";
            default -> "EXPIRED".equals(status) || "CANCELLED".equals(status) ? "EXPIRED" : "AWAITING_PAYMENT";
        };
        jdbc.update("UPDATE order_attempts SET status = ?, updated_at = ? WHERE id = ? AND status <> 'PAID'", attemptStatus, Shop.now(), attemptId);
        recompute(orderId);
        if (view.has("refunds")) applyRefunds(merchantId, orderId, view.path("refunds"));
    }

    /** The order's payment state from all its tries: any paid try makes it paid. */
    void recompute(long orderId) {
        Map<String, Object> o = jdbc.queryForMap("SELECT * FROM orders WHERE id = ? FOR UPDATE", orderId);
        String current = String.valueOf(o.get("payment_status"));
        List<String> tries = jdbc.queryForList("SELECT status FROM order_attempts WHERE order_id = ? ORDER BY id", String.class, orderId);
        String next;
        if (tries.contains("PAID")) next = "PAID";
        else if (tries.contains("CONFIRMING")) next = "CONFIRMING";
        else if (tries.contains("PENDING")) next = "PENDING";
        else if (!tries.isEmpty()) next = tries.get(tries.size() - 1);   // the latest try: AWAITING_PAYMENT, FAILED or EXPIRED
        else next = current;
        if ("PAID".equals(current) || next.equals(current)) return;
        // The payment definitively did not happen: the reserved items go back on sale (an uncertain one keeps them).
        if ("FAILED".equals(next) || "EXPIRED".equals(next)) release(orderId);
        long merchantId = ((Number) o.get("merchant_id")).longValue();
        jdbc.update("UPDATE orders SET payment_status = ?, paid_at = CASE WHEN ? = 'PAID' THEN ? ELSE paid_at END, updated_at = ? WHERE id = ?",
                next, next, Shop.now(), Shop.now(), orderId);
        if ("PAID".equals(next)) {
            shop.event("order.paid", merchantId, String.valueOf(o.get("order_ref")), String.valueOf(o.get("amount_minor")));
            sell(orderId);
            growth.orderConfirmed(String.valueOf(o.get("order_ref")));
            if (!"NONE".equals(o.get("delivery_method"))) jdbc.update("UPDATE orders SET delivery_status = 'PREPARING' WHERE id = ? AND delivery_status = 'NOT_NEEDED'", orderId);
            // A single-use link is closed once it is paid.
            if (o.get("link_id") != null) jdbc.update("UPDATE links SET status = 'USED', updated_at = ? WHERE id = ? AND reusable = FALSE AND status = 'ACTIVE'",
                    Shop.now(), o.get("link_id"));
        }
    }

    static final int HOLD_MINUTES = 35;   // a little longer than the PSP's checkout (30 minutes)

    /**
     * Reserves the order's counted items, atomically (stock >= quantity in the same UPDATE), so two customers can never
     * both pay for the last one. Returns why not (and reserves nothing) when something is short.
     */
    String reserve(long orderId) {
        List<Map<String, Object>> items = jdbc.queryForList("SELECT i.product_id, i.name, i.quantity, p.stock FROM order_items i JOIN products p ON p.id = i.product_id "
                + "WHERE i.order_id = ? AND p.stock IS NOT NULL", orderId);
        boolean booked = growth.hasBooking(orderId);
        if (items.isEmpty() && !booked) return null;
        List<Map<String, Object>> done = new ArrayList<>();
        for (Map<String, Object> it : items) {
            int q = ((Number) it.get("quantity")).intValue();
            if (jdbc.update("UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?", q, it.get("product_id"), q) == 0) {
                for (Map<String, Object> d : done) jdbc.update("UPDATE products SET stock = stock + ? WHERE id = ?", d.get("quantity"), d.get("product_id"));
                Integer left = jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, it.get("product_id"));
                return (left == null || left == 0 ? it.get("name") + " has just sold out" : "Only " + left + " " + it.get("name") + " left") + ".";
            }
            done.add(it);
        }
        if (booked) {
            String taken = growth.hold(orderId);
            if (taken != null) {
                for (Map<String, Object> d : done) jdbc.update("UPDATE products SET stock = stock + ? WHERE id = ?", d.get("quantity"), d.get("product_id"));
                return taken;
            }
        }
        jdbc.update("UPDATE orders SET stock_state = 'RESERVED', reserved_until = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().plusMinutes(HOLD_MINUTES)), orderId);
        return null;
    }

    /** Paid: the reservation becomes a sale. If it had been released meanwhile, the items are taken again or the order is flagged. */
    void sell(long orderId) {
        growth.confirm(orderId);
        if (jdbc.update("UPDATE orders SET stock_state = 'SOLD', reserved_until = NULL WHERE id = ? AND stock_state = 'RESERVED'", orderId) > 0) return;
        if (jdbc.update("UPDATE orders SET stock_state = 'SOLD' WHERE id = ? AND stock_state = 'RELEASED'", orderId) == 0) return;
        boolean short_ = false;
        for (Map<String, Object> it : jdbc.queryForList("SELECT i.product_id, i.quantity FROM order_items i JOIN products p ON p.id = i.product_id "
                + "WHERE i.order_id = ? AND p.stock IS NOT NULL", orderId)) {
            int q = ((Number) it.get("quantity")).intValue();
            if (jdbc.update("UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?", q, it.get("product_id"), q) == 0) {
                jdbc.update("UPDATE products SET stock = 0 WHERE id = ?", it.get("product_id"));
                short_ = true;
            }
        }
        if (short_) jdbc.update("UPDATE orders SET stock_short = TRUE WHERE id = ?", orderId);
    }

    /** Puts reserved (or, for a cancelled paid order, sold) items back on sale, once. */
    void release(long orderId) {
        if (jdbc.update("UPDATE orders SET stock_state = 'RELEASED', reserved_until = NULL WHERE id = ? AND stock_state IN ('RESERVED','SOLD')", orderId) == 0) return;
        growth.release(orderId);
        for (Map<String, Object> it : jdbc.queryForList("SELECT product_id, quantity FROM order_items WHERE order_id = ? AND product_id IS NOT NULL", orderId)) {
            jdbc.update("UPDATE products SET stock = stock + ? WHERE id = ? AND stock IS NOT NULL", it.get("quantity"), it.get("product_id"));
        }
    }

    /** A retry after a failed or expired payment reserves again (the items may have sold meanwhile). */
    String reserveAgain(long orderId) {
        Integer released = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id = ? AND stock_state = 'RELEASED'", Integer.class, orderId);
        if (released == null || released == 0) return null;
        return tx.execute(st -> {
            String why = reserve(orderId);
            if (why != null) st.setRollbackOnly();
            return why;
        });
    }

    /** Orders whose payment never started (for example while the PSP was down) give their items back after the hold. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void releaseExpiredHolds() {
        for (Long id : jdbc.queryForList("SELECT id FROM orders WHERE stock_state = 'RESERVED' AND payment_status = 'NOT_STARTED' AND reserved_until < ?",
                Long.class, Shop.now())) {
            release(id);
        }
    }

    /** Refunds as the PSP lists them: COMPLETED only once the customer has been paid back. */
    void applyRefunds(long merchantId, long orderId, JsonNode refunds) {
        for (JsonNode r : refunds) {
            long pspId = r.path("id").asLong(0);
            if (pspId == 0) continue;
            boolean done = "EXECUTED".equals(r.path("payoutStatus").asText());
            int n = jdbc.update("UPDATE refunds SET status = CASE WHEN ? THEN 'COMPLETED' ELSE status END, completed_at = CASE WHEN ? AND completed_at IS NULL THEN ? ELSE completed_at END "
                    + "WHERE psp_refund_id = ? AND merchant_id = ?", done, done, Shop.now(), pspId, merchantId);
            if (n == 0) {
                // a refund made outside Commerce (merchant portal or API): listed here too
                try {
                    jdbc.update("INSERT INTO refunds (order_id, merchant_id, amount_minor, reason, status, psp_refund_id, requested_by, created_at, completed_at) VALUES (?,?,?,?,?,?,?,?,?)",
                            orderId, merchantId, r.path("amountMinor").asLong(), "Refund", done ? "COMPLETED" : "REQUESTED", pspId, "PayPort", Shop.now(), done ? Shop.now() : null);
                } catch (DuplicateKeyException ignored) {
                    // recorded by another reader at the same moment
                }
            }
        }
        refundState(orderId);
    }

    void refundState(long orderId) {
        Map<String, Object> s = jdbc.queryForMap("SELECT COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN amount_minor END), 0) AS done, "
                + "COALESCE(SUM(CASE WHEN status = 'REQUESTED' THEN amount_minor END), 0) AS asked FROM refunds WHERE order_id = ?", orderId);
        long done = ((Number) s.get("done")).longValue(), asked = ((Number) s.get("asked")).longValue();
        long amount = jdbc.queryForObject("SELECT amount_minor FROM orders WHERE id = ?", Long.class, orderId);
        String state = asked > 0 ? "REQUESTED" : done >= amount ? "REFUNDED" : done > 0 ? "PARTIAL" : "NONE";
        jdbc.update("UPDATE orders SET refund_status = ?, refunded_minor = ?, updated_at = ? WHERE id = ?", state, done, Shop.now(), orderId);
    }

    /** The merchant refunds its own customer from its own balance (through the PSP). */
    public Map<String, Object> refund(Tokens.Merchant m, String ref, Map<String, Object> b) {
        Map<String, Object> o = jdbc.queryForList("SELECT * FROM orders WHERE order_ref = ? AND merchant_id = ?", ref, m.merchantId()).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No such order"));
        if ("CASH".equals(o.get("pay_method"))) throw new IllegalStateException("This order was paid in cash: refund the customer directly");
        if (!"PAID".equals(o.get("payment_status"))) throw new IllegalStateException("Only a paid order can be refunded");
        long amount = Shop.money(b.get("amountMinor"), "amount");
        String reason = Shop.text(b.get("reason"), 300, true, "reason");
        long orderId = ((Number) o.get("id")).longValue();
        long already = jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE order_id = ? AND status IN ('REQUESTED','COMPLETED')", Long.class, orderId);
        if (amount > ((Number) o.get("amount_minor")).longValue() - already) throw new IllegalArgumentException("That is more than is left to refund");
        String paidRef = jdbc.queryForList("SELECT psp_reference FROM order_attempts WHERE order_id = ? AND status = 'PAID' ORDER BY id LIMIT 1", String.class, orderId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("The payment is still being confirmed"));
        JsonNode r;
        try {
            r = psp.refund(Map.of("merchantId", m.merchantId(), "reference", paidRef, "amountMinor", amount, "reason", reason, "merchantUserId", m.userId()));
        } catch (Psp.Unavailable e) {
            throw new IllegalStateException("Refunds need PayPort Payments, which cannot be reached just now. Please try again in a few minutes.");
        } catch (Psp.Refused e) {
            throw new IllegalStateException(e.getMessage());
        }
        long pspId = r.path("refundId").asLong();
        try {
            jdbc.update("INSERT INTO refunds (order_id, merchant_id, amount_minor, reason, status, psp_refund_id, requested_by, created_at) VALUES (?,?,?,?, 'REQUESTED', ?,?,?)",
                    orderId, m.merchantId(), amount, reason, pspId, Shop.clip(m.name(), 120), Shop.now());
        } catch (DuplicateKeyException ignored) {
            // the event reader recorded it first
        }
        applyRefunds(m.merchantId(), orderId, r.path("refunds"));
        shop.event("refund.requested", m.merchantId(), ref, String.valueOf(amount));
        return merchantOrder(m.merchantId(), ref);
    }

    // ─── reconciliation: status for anything still open (covers missed events) ─

    @Scheduled(fixedDelayString = "${commerce.reconcile-ms:60000}", initialDelay = 30_000)
    public void reconcile() {
        Timestamp quiet = Timestamp.valueOf(LocalDateTime.now().minusSeconds(45));
        Timestamp old = Timestamp.valueOf(LocalDateTime.now().minusDays(3));
        List<Map<String, Object>> open = jdbc.queryForList("SELECT a.merchant_id, a.psp_reference FROM order_attempts a WHERE a.status IN ('AWAITING_PAYMENT','PENDING','CONFIRMING') "
                + "AND a.updated_at < ? AND a.created_at > ? ORDER BY a.updated_at LIMIT 200", quiet, old);
        open.addAll(jdbc.queryForList("SELECT a.merchant_id, a.psp_reference FROM refunds r JOIN order_attempts a ON a.order_id = r.order_id AND a.status = 'PAID' "
                + "WHERE r.status = 'REQUESTED' AND r.created_at > ? LIMIT 200", Timestamp.valueOf(LocalDateTime.now().minusDays(60))));
        for (Map<String, Object> a : open) {
            long merchant = ((Number) a.get("merchant_id")).longValue();
            String ref = (String) a.get("psp_reference");
            try {
                apply(merchant, ref, psp.status(merchant, ref));
            } catch (Psp.Unavailable e) {
                return;   // the PSP is down: try the lot again next time
            } catch (Psp.Refused e) {
                jdbc.update("UPDATE order_attempts SET updated_at = ? WHERE merchant_id = ? AND psp_reference = ?", Shop.now(), merchant, ref);
            } catch (RuntimeException e) {
                log.warn("[COMMERCE] reconcile {} failed: {}", ref, e.getMessage());
            }
        }
    }

    /** Refresh one order from the PSP now (the customer is looking at it); silent when the PSP is down. */
    public void refresh(String ref) {
        for (Map<String, Object> a : jdbc.queryForList("SELECT a.merchant_id, a.psp_reference FROM order_attempts a JOIN orders o ON o.id = a.order_id "
                + "WHERE o.order_ref = ? AND a.status IN ('AWAITING_PAYMENT','PENDING','CONFIRMING')", ref)) {
            try {
                apply(((Number) a.get("merchant_id")).longValue(), (String) a.get("psp_reference"), psp.status(((Number) a.get("merchant_id")).longValue(), (String) a.get("psp_reference")));
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    /** An order's lines for the customer's page. */
    public List<Map<String, Object>> items(long orderId, String ccy) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT name, unit_minor, quantity FROM order_items WHERE order_id = ? ORDER BY id", orderId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.get("name"));
            m.put("quantity", r.get("quantity"));
            m.put("line", ccy + " " + Shop.major(((Number) r.get("unit_minor")).longValue() * ((Number) r.get("quantity")).intValue()));
            out.add(m);
        }
        return out;
    }

    // ─── merchant views ──────────────────────────────────────────────────────

    public Map<String, Object> merchantOrderView(Map<String, Object> o) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("orderRef", o.get("order_ref"));
        v.put("itemName", o.get("item_name"));
        v.put("quantity", o.get("quantity"));
        v.put("amountMinor", o.get("amount_minor"));
        v.put("currency", o.get("currency"));
        v.put("customerName", o.get("customer_name"));
        v.put("customerPhone", o.get("customer_phone"));
        v.put("note", o.get("note"));
        v.put("orderStatus", o.get("order_status"));
        v.put("paymentStatus", o.get("payment_status"));
        v.put("refundStatus", o.get("refund_status"));
        v.put("payChoice", o.get("pay_choice"));
        v.put("deliveryZone", o.get("delivery_zone"));
        v.put("booking", growth.bookingOf(((Number) o.get("id")).longValue()).orElse(null));
        v.put("refundedMinor", o.get("refunded_minor"));
        v.put("source", o.get("link_id") != null ? "LINK" : "STORE");
        v.put("deliveryMethod", o.get("delivery_method"));
        v.put("deliveryFeeMinor", o.get("delivery_fee_minor"));
        v.put("deliveryAddress", o.get("delivery_address"));
        v.put("deliveryStatus", o.get("delivery_status"));
        v.put("stockShort", Shop.bool(o.get("stock_short")));
        v.put("payMethod", o.get("pay_method"));
        v.put("discountCode", o.get("discount_code"));
        v.put("discountMinor", o.get("discount_minor"));
        v.put("createdAt", String.valueOf(o.get("created_at")));
        v.put("paidAt", o.get("paid_at") == null ? null : String.valueOf(o.get("paid_at")));
        return v;
    }

    public List<Map<String, Object>> merchantOrders(long merchantId, String payment, String q) {
        StringBuilder sql = new StringBuilder("SELECT * FROM orders WHERE merchant_id = ?");
        List<Object> args = new ArrayList<>(List.of(merchantId));
        if (payment != null && !payment.isBlank()) {
            sql.append(" AND payment_status = ?");
            args.add(payment.toUpperCase(Locale.ROOT));
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (order_ref LIKE ? OR item_name LIKE ? OR customer_name LIKE ? OR customer_phone LIKE ?)");
            String like = "%" + q.trim().replace("%", "") + "%";
            for (int i = 0; i < 4; i++) args.add(like);
        }
        sql.append(" ORDER BY id DESC LIMIT 500");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> o : jdbc.queryForList(sql.toString(), args.toArray())) out.add(merchantOrderView(o));
        return out;
    }

    public Map<String, Object> merchantOrder(long merchantId, String ref) {
        Map<String, Object> o = jdbc.queryForList("SELECT * FROM orders WHERE order_ref = ? AND merchant_id = ?", ref, merchantId).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No such order"));
        Map<String, Object> v = merchantOrderView(o);
        v.put("items", jdbc.queryForList("SELECT product_id AS productId, name, unit_minor AS unitMinor, quantity FROM order_items WHERE order_id = ? ORDER BY id", o.get("id")));
        v.put("refunds", jdbc.queryForList("SELECT amount_minor AS amountMinor, reason, status, created_at AS createdAt, completed_at AS completedAt FROM refunds "
                + "WHERE order_id = ? ORDER BY id", o.get("id")).stream().map(r -> {
                    Map<String, Object> x = new LinkedHashMap<>(r);
                    x.put("createdAt", String.valueOf(r.get("createdAt")));
                    x.put("completedAt", r.get("completedAt") == null ? null : String.valueOf(r.get("completedAt")));
                    return x;
                }).toList());
        return v;
    }

    public Map<String, Object> setOrderStatus(long merchantId, String ref, String status) {
        String s = String.valueOf(status).toUpperCase(Locale.ROOT);
        if (!Set.of("OPEN", "FULFILLED", "CANCELLED").contains(s)) throw new IllegalArgumentException("Choose the order status");
        Map<String, Object> o = jdbc.queryForList("SELECT * FROM orders WHERE order_ref = ? AND merchant_id = ?", ref, merchantId).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No such order"));
        if ("CANCELLED".equals(s) && "PAID".equals(o.get("payment_status")) && !"REFUNDED".equals(o.get("refund_status")))
            throw new IllegalStateException("Refund a paid order before cancelling it");
        jdbc.update("UPDATE orders SET order_status = ?, updated_at = ? WHERE id = ?", s, Shop.now(), o.get("id"));
        if ("CANCELLED".equals(s)) release(((Number) o.get("id")).longValue());
        return merchantOrder(merchantId, ref);
    }

    /** Pickup: preparing, ready, collected. Delivery: preparing, out for delivery, delivered. The last step fulfils the order. */
    public Map<String, Object> setDelivery(long merchantId, String ref, String status) {
        String s = String.valueOf(status).toUpperCase(Locale.ROOT);
        Map<String, Object> o = jdbc.queryForList("SELECT * FROM orders WHERE order_ref = ? AND merchant_id = ?", ref, merchantId).stream().findFirst()
                .orElseThrow(() -> new Shop.NotFound("No such order"));
        if (!"PAID".equals(o.get("payment_status")) && !"ON_DELIVERY".equals(o.get("payment_status"))) throw new IllegalStateException("Only a paid or pay-on-delivery order can be prepared or sent");
        String method = String.valueOf(o.get("delivery_method"));
        Set<String> allowed = "DELIVERY".equals(method) ? Set.of("PREPARING", "OUT", "DELIVERED") : "PICKUP".equals(method) ? Set.of("PREPARING", "READY", "COLLECTED") : Set.of();
        if (!allowed.contains(s)) throw new IllegalArgumentException("Choose the next step for this order");
        boolean done = "DELIVERED".equals(s) || "COLLECTED".equals(s);
        jdbc.update("UPDATE orders SET delivery_status = ?, order_status = CASE WHEN ? THEN 'FULFILLED' ELSE order_status END, updated_at = ? WHERE id = ?",
                s, done, Shop.now(), o.get("id"));
        shop.event("delivery." + s.toLowerCase(Locale.ROOT), merchantId, ref, method);
        growth.deliveryStep(ref, s);
        return merchantOrder(merchantId, ref);
    }

    /** Sales and the Phase 1 success measures, from Commerce's own records. */
    public Map<String, Object> overview(long merchantId) {
        Map<String, Object> v = new LinkedHashMap<>();
        List<Map<String, Object>> sales = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT currency, "
                + "COALESCE(SUM(CASE WHEN paid_at >= CURDATE() THEN amount_minor END), 0) AS today, "
                + "COALESCE(SUM(CASE WHEN paid_at >= CURDATE() - INTERVAL 6 DAY THEN amount_minor END), 0) AS week, "
                + "COALESCE(SUM(CASE WHEN paid_at >= CURDATE() - INTERVAL 29 DAY THEN amount_minor END), 0) AS month, "
                + "COALESCE(SUM(CASE WHEN paid_at >= CURDATE() - INTERVAL 29 DAY THEN 1 END), 0) AS orders_month "
                + "FROM orders WHERE merchant_id = ? AND payment_status = 'PAID' GROUP BY currency ORDER BY currency", merchantId)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("currency", r.get("currency"));
            s.put("todayMinor", ((Number) r.get("today")).longValue());
            s.put("weekMinor", ((Number) r.get("week")).longValue());
            s.put("monthMinor", ((Number) r.get("month")).longValue());
            s.put("ordersMonth", ((Number) r.get("orders_month")).longValue());
            sales.add(s);
        }
        v.put("sales", sales);
        Map<String, Object> c = jdbc.queryForMap("SELECT "
                + "(SELECT COUNT(*) FROM orders WHERE merchant_id = ? AND payment_status IN ('PENDING','CONFIRMING')) AS confirming, "
                + "(SELECT COUNT(*) FROM orders WHERE merchant_id = ? AND payment_status IN ('PAID','ON_DELIVERY') AND order_status = 'OPEN') AS to_fulfil, "
                + "(SELECT COUNT(*) FROM links WHERE merchant_id = ? AND status = 'ACTIVE') AS links, "
                + "(SELECT COUNT(*) FROM products WHERE merchant_id = ? AND status = 'ACTIVE') AS products", merchantId, merchantId, merchantId, merchantId);
        v.put("beingConfirmed", ((Number) c.get("confirming")).longValue());
        v.put("toFulfil", ((Number) c.get("to_fulfil")).longValue());
        v.put("activeLinks", ((Number) c.get("links")).longValue());
        v.put("activeProducts", ((Number) c.get("products")).longValue());
        // Success measures: first link after first sign-in; checkouts started that end paid.
        Map<String, Object> m = jdbc.queryForMap("SELECT "
                + "(SELECT MIN(occurred_at) FROM commerce_events WHERE merchant_id = ? AND event_type = 'merchant.first_seen') AS first_seen, "
                + "(SELECT MIN(occurred_at) FROM commerce_events WHERE merchant_id = ? AND event_type = 'link.created') AS first_link, "
                + "(SELECT COUNT(DISTINCT subject_ref) FROM commerce_events WHERE merchant_id = ? AND event_type = 'checkout.started' AND occurred_at >= NOW() - INTERVAL 30 DAY) AS started, "
                + "(SELECT COUNT(*) FROM commerce_events WHERE merchant_id = ? AND event_type = 'order.paid' AND occurred_at >= NOW() - INTERVAL 30 DAY) AS paid",
                merchantId, merchantId, merchantId, merchantId);
        Long minutes = null;
        if (m.get("first_seen") != null && m.get("first_link") != null) {
            minutes = java.time.Duration.between(Shop.time(m.get("first_seen")), Shop.time(m.get("first_link"))).toMinutes();
        }
        v.put("minutesToFirstLink", minutes);
        long started = ((Number) m.get("started")).longValue(), paid = ((Number) m.get("paid")).longValue();
        v.put("checkoutsStarted30d", started);
        v.put("checkoutsPaid30d", paid);
        v.put("conversion30d", started == 0 ? null : Math.min(1.0, (double) paid / started));
        return v;
    }
}
