package zw.co.payport.gateway.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.Refund;
import zw.co.payport.gateway.events.PlatformEvents;
import zw.co.payport.gateway.payments.TransactionOutcomeService;
import zw.co.payport.gateway.repository.CheckoutSessionRepository;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.service.CheckoutSessionService;
import zw.co.payport.gateway.service.RefundService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The only door between Payport Commerce and the PSP (PspPaymentsApi in the Phase 0 assessment). Commerce runs as
 * its own service with its own database, so it keeps working when the PSP is down (owner, 2026-10-31); it asks the
 * PSP for money things only through here, and the PSP stays the only authority on money:
 *
 *   - create a checkout for an order (idempotent per merchant and order reference);
 *   - read a checkout's status and customer-facing outcome (PAID only when the PSP has confirmed it);
 *   - ask for a refund (the merchant's own refund, from its own balance, RefundService);
 *   - read the platform event stream after a cursor (Commerce keeps its cursor; replays are harmless);
 *   - read a merchant's name and enabled currencies.
 *
 * Commerce authenticates with the service key PAYPORT_COMMERCE_KEY (constant-time compare); without a key configured
 * this door is closed. Commerce never reads PSP tables and never writes the ledger.
 */
@Service
public class CommerceFacade {

    private final CheckoutSessionService checkouts;
    private final CheckoutSessionRepository sessions;
    private final MerchantRepository merchants;
    private final RefundService refunds;
    private final PlatformEvents events;
    private final TransactionOutcomeService outcomes;
    private final JdbcTemplate jdbc;
    private final String key;
    private final String consumerBase;
    private final zw.co.payport.gateway.merchant.MerchantPaymentMethods methods;

    public CommerceFacade(CheckoutSessionService checkouts, CheckoutSessionRepository sessions, MerchantRepository merchants, RefundService refunds,
                          PlatformEvents events, TransactionOutcomeService outcomes, JdbcTemplate jdbc,
                          @Value("${payport.commerce.key:}") String key,
                          @Value("${payport.consumer.base-url:https://app.payport.africa}") String consumerBase,
                          @Value("${payport.environment:production}") String environment,
                          zw.co.payport.gateway.merchant.MerchantPaymentMethods methods) {
        this.methods = methods;
        this.checkouts = checkouts;
        this.sessions = sessions;
        this.merchants = merchants;
        this.refunds = refunds;
        this.events = events;
        this.outcomes = outcomes;
        this.jdbc = jdbc;
        this.key = key == null ? "" : key.trim();
        String base = consumerBase == null ? "" : consumerBase.replaceAll("/+$", "");
        // Staging never sends shoppers to the live payment page (CONSUMER_BASE_URL left at its live default).
        if ("staging".equalsIgnoreCase(environment) && base.equals("https://app.payport.africa")) base = "https://app-staging.payport.africa";
        this.consumerBase = base;
    }

    /** The service key check: a missing or wrong key is refused; no key configured means the door is closed. */
    public void requireKey(String presented) {
        if (key.length() < 24) throw new SecurityException("Payport Commerce is not connected");
        if (presented == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), presented.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Wrong service key");
        }
    }

    private Merchant merchant(Long id) {
        Merchant m = id == null ? null : merchants.findById(id).orElse(null);
        if (m == null) throw new IllegalArgumentException("No such merchant");
        return m;
    }

    public record NewCheckout(Long merchantId, String reference, Long amountMinor, String currency, String description,
                              String successUrl, String cancelUrl, String orderRef, String method) {}

    /** One checkout per merchant and reference: a retry returns the same checkout instead of a second one. */
    public Map<String, Object> checkout(NewCheckout b) {
        Merchant m = merchant(b.merchantId());
        if (!"ACTIVE".equals(m.getStatus())) throw new IllegalStateException("This merchant cannot take payments at the moment");
        String ref = b.reference() == null ? "" : b.reference().trim();
        if (!ref.matches("[A-Za-z0-9_-]{6,60}")) throw new IllegalArgumentException("Invalid reference");
        var existing = sessions.findByMerchantIdAndReference(m.getId(), ref);
        if (existing.isPresent()) return view(existing.get());
        String meta = "{\"source\":\"commerce\",\"orderRef\":\"" + (b.orderRef() == null ? "" : b.orderRef().replaceAll("[^A-Za-z0-9_-]", "")) + "\"}";
        String description = b.description() == null ? null : b.description().length() > 200 ? b.description().substring(0, 200) : b.description();
        CheckoutSession s;
        try {
            // Store payments are test mode (sandbox) until Payport switches them to live with the owner's approval.
            s = checkouts.createSession(m, null, new CheckoutSessionService.CreateSessionRequest(ref, b.amountMinor(), b.currency(), description,
                    null, null, b.successUrl(), b.cancelUrl(), meta), methods.commerceLive());
            String pick = methods.preferred(m.getId(), b.method());
            if (pick != null) jdbc.update("UPDATE checkout_sessions SET preferred_method = ? WHERE id = ?", pick, s.getId());
        } catch (CheckoutSessionService.CurrencyNotEnabledException e) {
            throw new IllegalStateException(e.getMessage());
        } catch (RuntimeException e) {
            // two requests at the same moment: the other one created it
            var again = sessions.findByMerchantIdAndReference(m.getId(), ref);
            if (again.isPresent()) return view(again.get());
            throw new IllegalArgumentException(e.getMessage());
        }
        return view(s);
    }

    public Map<String, Object> status(Long merchantId, String reference) {
        CheckoutSession s = sessions.findByMerchantIdAndReference(merchantId, reference == null ? "" : reference.trim())
                .orElseThrow(() -> new IllegalArgumentException("No such checkout"));
        return view(s);
    }

    /**
     * What Commerce may show: PAID only when the PSP confirmed it; an unclear provider answer is UNCERTAIN ("being
     * confirmed"), never paid. Refunds are listed with the payout status from the register.
     */
    Map<String, Object> view(CheckoutSession s) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("checkoutSessionId", s.getId());
        v.put("reference", s.getReference());
        v.put("merchantId", s.getMerchantId());
        v.put("status", s.getStatus());
        v.put("amountMinor", s.getAmountCents());
        v.put("currency", s.getCurrency());
        v.put("livemode", Boolean.TRUE.equals(s.getLivemode()));
        v.put("checkoutUrl", consumerBase + "/checkout/" + s.getId());
        v.put("expiresAt", s.getExpiresAt() == null ? null : s.getExpiresAt().toString());
        String outcome = switch (String.valueOf(s.getStatus())) {
            case "PAID", "PARTIALLY_REFUNDED", "REFUNDED" -> "PAID";
            case "FAILED" -> "FAILED";
            case "EXPIRED", "CANCELLED" -> "NOT_PAID";
            case "PENDING" -> "PENDING";
            default -> "NOT_STARTED";
        };
        if ("PENDING".equals(outcome) && s.getTransactionReference() != null) {
            try {
                var o = outcomes.current(s.getTransactionReference());
                if (o.isPresent() && o.get().outcome() == TransactionOutcomeService.Outcome.NO_RESPONSE) outcome = "UNCERTAIN";
            } catch (RuntimeException ignored) {
                // the session status is still right
            }
        }
        v.put("outcome", outcome);
        // The provider's own words for a failed or unclear payment (the same reason the app and WhatsApp show).
        if (!"PAID".equals(outcome) && s.getTransactionReference() != null) {
            try {
                outcomes.current(s.getTransactionReference()).map(TransactionOutcomeService.Result::message)
                        .filter(m -> m != null && !m.isBlank()).ifPresent(m -> v.put("reason", m.length() > 300 ? m.substring(0, 300) : m));
            } catch (RuntimeException ignored) {
                // no reason to show
            }
        }
        List<Map<String, Object>> rf = jdbc.queryForList("SELECT r.id, r.amount_cents AS amountMinor, r.reversal_ref AS reversalRef, "
                + "COALESCE(v.status, CASE WHEN r.reversal_ref IS NULL AND ? = FALSE THEN 'EXECUTED' ELSE 'AWAITING_PAYOUT' END) AS payoutStatus, r.created_at AS createdAt FROM refunds r "
                + "LEFT JOIN reversal_requests v ON v.reversal_ref = r.reversal_ref WHERE r.checkout_session_id = ? AND r.status = 'COMPLETED' ORDER BY r.id", Boolean.TRUE.equals(s.getLivemode()), s.getId());
        v.put("refunds", rf);
        return v;
    }

    public record NewRefund(Long merchantId, String reference, Long amountMinor, String reason, Long merchantUserId) {}

    /** The merchant refunds its own customer from its own balance (owner, 2026-10-31). */
    public Map<String, Object> refund(NewRefund b) {
        CheckoutSession s = sessions.findByMerchantIdAndReference(b.merchantId(), b.reference() == null ? "" : b.reference().trim())
                .orElseThrow(() -> new IllegalArgumentException("No such checkout"));
        if (b.reason() == null || b.reason().trim().length() < 3) throw new IllegalArgumentException("Give the reason for the refund");
        Refund r;
        try {
            r = refunds.createRefund(s, new RefundService.RefundRequest(b.amountMinor(), b.reason().trim()), "COMMERCE", b.merchantUserId() == null ? 0L : b.merchantUserId());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage());
        }
        Map<String, Object> v = view(sessions.findById(s.getId()).orElse(s));
        v.put("refundId", r.getId());
        return v;
    }

    public List<Map<String, Object>> events(long after, int limit) {
        return events.after(after, limit, null);
    }

    public Map<String, Object> merchantInfo(Long merchantId) {
        Merchant m = merchant(merchantId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("merchantId", m.getId());
        v.put("businessName", m.getBusinessName());
        v.put("status", m.getStatus());
        v.put("defaultCurrency", m.getDefaultCurrency());
        v.put("methods", methods.forMerchant(m.getId()));
        v.put("storePaymentsLive", methods.commerceLive());
        v.put("currencies", jdbc.queryForList("SELECT DISTINCT currency FROM merchant_enablements WHERE merchant_id = ? AND status = 'ENABLED' ORDER BY currency",
                String.class, m.getId()));
        return v;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.service.SmsNotificationService sms;

    static final int MESSAGES_PER_DAY = 500;

    public record NewMessage(Long merchantId, String to, String phone, String text, String reference, String channel) {}

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.whatsapp.WhatsAppService whatsapp;

    /**
     * A text about a store order: to the merchant (its registered contact phone; Commerce never chooses that number) or
     * to the customer who placed the order. Short, capped per merchant per day; the number is not stored.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.notifications.PushNotificationService push;

    public Map<String, Object> message(NewMessage b) {
        if ("STORE_USER".equals(b.to())) return storeUserMessage(b);
        // A seller's chat message to an app or website customer: a push to their phone (no SMS).
        if ("PUSH".equals(b.to())) {
            String t = b.text() == null ? "" : b.text().replaceAll("[\\u0000-\\u001F]", " ").trim();
            if (t.isEmpty() || t.length() > 300) throw new IllegalArgumentException("A message is 1 to 300 characters");
            int colon = t.indexOf(": ");
            boolean sent = push != null && push.pushToPhone(b.phone(), colon > 0 && colon < 60 ? t.substring(0, colon) : "Payport Store",
                    colon > 0 && colon < 60 ? t.substring(colon + 2) : t, Map.of("type", "chat", "reference", b.reference() == null ? "" : b.reference()));
            return Map.of("queued", sent);
        }
        Merchant m = merchant(b.merchantId());
        if (!"ACTIVE".equals(m.getStatus())) throw new IllegalStateException("This merchant cannot send messages at the moment");
        String text = b.text() == null ? "" : b.text().replaceAll("[\\u0000-\\u001F]", " ").trim();
        if (text.isEmpty() || text.length() > 300) throw new IllegalArgumentException("A message is 1 to 300 characters");
        boolean toMerchant = "MERCHANT".equals(b.to());
        boolean chat = "CHAT_CUSTOMER".equals(b.to());
        String phone = toMerchant ? m.getContactPhone() : b.phone() == null ? null : b.phone().replaceAll("[\\s()-]", "");
        if (phone == null || !phone.matches("\\+?[0-9]{9,15}")) throw new IllegalArgumentException(toMerchant ? "The merchant has no contact phone" : "Invalid phone number");
        Integer today = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_messages WHERE merchant_id = ? AND created_at >= CURDATE()", Integer.class, m.getId());
        if (today != null && today >= MESSAGES_PER_DAY) throw new IllegalStateException("Today's message limit for this store has been reached");
        String ref = b.reference() == null ? null : b.reference().replaceAll("[^A-Za-z0-9_-]", "");
        if (ref != null && ref.length() > 60) ref = ref.substring(0, 60);
        jdbc.update("INSERT INTO commerce_messages (merchant_id, kind, reference, created_at) VALUES (?,?,?,NOW(6))", m.getId(), toMerchant ? "MERCHANT" : chat ? "CHAT" : "CUSTOMER", ref);
        // A seller's chat answer to a WhatsApp customer goes back on WhatsApp; to a USSD customer by SMS.
        if (chat && "WHATSAPP".equals(b.channel()) && whatsapp != null) {
            whatsapp.outbox(phone.replaceAll("\\D", ""), text + "\n\nReply here: send MENU, then Shop, the store, and Message the seller.", "STORE_CHAT");
            return Map.of("queued", true);
        }
        if (sms != null) sms.sendText(phone, text, ref == null ? "STORE" : ref, toMerchant ? "STORE_ORDER_ALERT" : chat ? "STORE_CHAT" : "STORE_ORDER_UPDATE");
        // Order updates also reach the customer's app as a push.
        if (!toMerchant && !chat && push != null) push.pushToPhone(phone, "Your order", text, Map.of("type", "order", "reference", ref == null ? "" : ref));
        return Map.of("queued", true);
    }

    /**
     * A text to a Payport Store user that no merchant sends (their password reset code). At most three an hour and ten
     * a day per number; the number is kept only as a hash for that count.
     */
    Map<String, Object> storeUserMessage(NewMessage b) {
        String text = b.text() == null ? "" : b.text().replaceAll("[\\u0000-\\u001F]", " ").trim();
        if (text.isEmpty() || text.length() > 160) throw new IllegalArgumentException("A message is 1 to 160 characters");
        String phone = b.phone() == null ? null : b.phone().replaceAll("[\\s()-]", "");
        if (phone == null || !phone.matches("\\+?[0-9]{9,15}")) throw new IllegalArgumentException("Invalid phone number");
        String who = "SU-" + StoreUsers.sha(phone.replaceAll("\\D", "")).substring(0, 40);
        Integer hour = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_messages WHERE merchant_id = 0 AND reference = ? AND created_at >= NOW() - INTERVAL 1 HOUR", Integer.class, who);
        Integer day = jdbc.queryForObject("SELECT COUNT(*) FROM commerce_messages WHERE merchant_id = 0 AND reference = ? AND created_at >= NOW() - INTERVAL 1 DAY", Integer.class, who);
        if ((hour != null && hour >= 3) || (day != null && day >= 10)) throw new IllegalStateException("Too many messages to this number. Please try again later.");
        jdbc.update("INSERT INTO commerce_messages (merchant_id, kind, reference, created_at) VALUES (0, 'STORE_USER', ?, NOW(6))", who);
        if (sms != null) sms.sendText(phone, text, "STORE", "STORE_USER_CODE");
        return Map.of("queued", true);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.care.CareService care;

    public record NewReport(Long merchantId, String about, String reason, String store, String storeSlug, Long chatId, String details, String reporterPhone) {}

    /**
     * A Payport Store report: a customer reporting a seller, or a seller reporting a customer. It becomes a customer
     * care complaint (Customer care > Complaints) for staff to follow up; the answer carries the complaint reference.
     */
    public Map<String, Object> report(NewReport b) {
        if (care == null) throw new IllegalStateException("Customer care is not available");
        Merchant m = merchant(b.merchantId());
        boolean aboutSeller = !"CUSTOMER".equals(b.about());
        String reason = b.reason() == null ? "Not given" : b.reason().replaceAll("[\\u0000-\\u001F]", " ").trim();
        String details = b.details() == null ? "" : b.details().replaceAll("[\\u0000-\\u0009\\u000B-\\u001F]", " ").trim();
        String text = (aboutSeller ? "A customer reported the seller " : "The seller reported a customer of ") + clipText(b.store(), 80)
                + " (merchant " + m.getId() + ", " + clipText(m.getBusinessName(), 80) + ") on Payport Store. Reason: " + clipText(reason, 60) + "."
                + (b.chatId() == null ? "" : " Chat " + b.chatId() + ".") + (details.isEmpty() ? "" : " Details: " + clipText(details, 1200));
        String phone = aboutSeller ? b.reporterPhone() : null;
        String reference = "STORE-" + (b.storeSlug() == null ? m.getId() : b.storeSlug().replaceAll("[^A-Za-z0-9-]", "")) + (b.chatId() == null ? "" : "-CHAT-" + b.chatId());
        Map<String, Object> c = care.create(new zw.co.payport.gateway.care.CareService.NewComplaint(aboutSeller ? "S_SELLER_CONDUCT" : "S_CUSTOMER_CONDUCT", "STORE",
                phone, null, clipText(reference, 100), text), "commerce");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("complaintRef", c.get("complaintRef") != null ? c.get("complaintRef") : c.get("complaint_ref"));
        return out;
    }

    static String clipText(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** For Payport Commerce's sign-in: who the merchant user behind a merchant portal token is. */
    public Map<String, Object> identity(Merchant m, zw.co.payport.gateway.entity.MerchantUser u) {
        Map<String, Object> v = merchantInfo(m.getId());
        v.put("userId", u == null ? null : u.getId());
        v.put("userName", u == null ? null : u.getFullName());
        v.put("role", u == null ? "OWNER" : String.valueOf(u.getRole()).toUpperCase(Locale.ROOT));
        return v;
    }
}
