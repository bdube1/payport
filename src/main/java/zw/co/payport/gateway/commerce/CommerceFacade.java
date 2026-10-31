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
 * The only door between PayPort Commerce and the PSP (PspPaymentsApi in the Phase 0 assessment). Commerce runs as
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

    public CommerceFacade(CheckoutSessionService checkouts, CheckoutSessionRepository sessions, MerchantRepository merchants, RefundService refunds,
                          PlatformEvents events, TransactionOutcomeService outcomes, JdbcTemplate jdbc,
                          @Value("${payport.commerce.key:}") String key,
                          @Value("${payport.consumer.base-url:https://app.payport.africa}") String consumerBase) {
        this.checkouts = checkouts;
        this.sessions = sessions;
        this.merchants = merchants;
        this.refunds = refunds;
        this.events = events;
        this.outcomes = outcomes;
        this.jdbc = jdbc;
        this.key = key == null ? "" : key.trim();
        this.consumerBase = consumerBase == null ? "" : consumerBase.replaceAll("/+$", "");
    }

    /** The service key check: a missing or wrong key is refused; no key configured means the door is closed. */
    public void requireKey(String presented) {
        if (key.length() < 24) throw new SecurityException("PayPort Commerce is not connected");
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
                              String successUrl, String cancelUrl, String orderRef) {}

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
            s = checkouts.createSession(m, null, new CheckoutSessionService.CreateSessionRequest(ref, b.amountMinor(), b.currency(), description,
                    null, null, b.successUrl(), b.cancelUrl(), meta));
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
        List<Map<String, Object>> rf = jdbc.queryForList("SELECT r.id, r.amount_cents AS amountMinor, r.reversal_ref AS reversalRef, "
                + "COALESCE(v.status, 'AWAITING_PAYOUT') AS payoutStatus, r.created_at AS createdAt FROM refunds r "
                + "LEFT JOIN reversal_requests v ON v.reversal_ref = r.reversal_ref WHERE r.checkout_session_id = ? AND r.status = 'COMPLETED' ORDER BY r.id", s.getId());
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
        v.put("currencies", jdbc.queryForList("SELECT DISTINCT currency FROM merchant_enablements WHERE merchant_id = ? AND status = 'ENABLED' ORDER BY currency",
                String.class, m.getId()));
        return v;
    }

    /** For PayPort Commerce's sign-in: who the merchant user behind a merchant portal token is. */
    public Map<String, Object> identity(Merchant m, zw.co.payport.gateway.entity.MerchantUser u) {
        Map<String, Object> v = merchantInfo(m.getId());
        v.put("userId", u == null ? null : u.getId());
        v.put("userName", u == null ? null : u.getFullName());
        v.put("role", u == null ? "OWNER" : String.valueOf(u.getRole()).toUpperCase(Locale.ROOT));
        return v;
    }
}
