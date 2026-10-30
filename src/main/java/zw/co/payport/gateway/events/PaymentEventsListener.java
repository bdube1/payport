package zw.co.payport.gateway.events;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every payment status change becomes one platform event, in the same thread and transaction as the change:
 * payment.initiated, payment.confirmed, payment.completed, payment.failed, payment.refund_requested, payment.refunded,
 * or payment.updated for the steps in between. The payload is what reporting and fraud models need, without
 * personal data: reference, status, product, channel, amount, currency, provider and attempt number.
 */
@Component
public class PaymentEventsListener {

    private final JdbcTemplate jdbc;
    private final PlatformEvents events;

    public PaymentEventsListener(JdbcTemplate jdbc, PlatformEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    static String type(String status) {
        if (status == null) return "payment.updated";
        return switch (status) {
            case "INITIATED" -> "payment.initiated";
            case "PAYMENT_CONFIRMED" -> "payment.confirmed";
            case "COMPLETED" -> "payment.completed";
            case "PAYMENT_FAILED", "PAYMENT_TIMEOUT", "FULFILMENT_FAILED" -> "payment.failed";
            case "REFUND_PENDING" -> "payment.refund_requested";
            case "REFUNDED" -> "payment.refunded";
            default -> "payment.updated";
        };
    }

    @EventListener
    public void onStatus(TransactionOrchestratorService.TransactionStatusChangedEvent e) {
        if (e == null || e.transactionReference() == null) return;
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("SELECT t.reference, t.status, t.channel, t.amount_cents, t.charge_cents, t.currency, t.payment_provider_code, "
                    + "t.payment_attempt_count, p.code AS product, p.category, cs.id AS session_id, cs.merchant_id, cs.livemode "
                    + "FROM transactions t LEFT JOIN product_types p ON p.id = t.product_type_id "
                    + "LEFT JOIN checkout_sessions cs ON cs.transaction_reference = t.reference WHERE t.reference = ?", e.transactionReference());
        } catch (RuntimeException ex) {
            return;
        }
        if (rows.isEmpty()) return;
        Map<String, Object> t = rows.get(0);
        String status = e.newStatus() == null ? String.valueOf(t.get("status")) : e.newStatus();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reference", t.get("reference"));
        p.put("status", status);
        p.put("product", t.get("product"));
        p.put("category", t.get("category"));
        p.put("channel", t.get("channel"));
        p.put("amountMinor", t.get("amount_cents"));
        p.put("chargeMinor", t.get("charge_cents"));
        p.put("currency", t.get("currency"));
        p.put("provider", t.get("payment_provider_code"));
        p.put("attempt", t.get("payment_attempt_count"));
        if (t.get("session_id") != null) p.put("checkoutSessionId", t.get("session_id"));
        Long merchant = t.get("merchant_id") == null ? null : ((Number) t.get("merchant_id")).longValue();
        boolean live = Boolean.TRUE.equals(t.get("livemode")) || Integer.valueOf(1).equals(t.get("livemode"));
        Object attempt = t.get("payment_attempt_count") == null ? 0 : t.get("payment_attempt_count");
        events.record(type(status), merchant, e.transactionReference(), "payment:" + e.transactionReference() + ":" + status + ":" + attempt, live, p);
    }
}
