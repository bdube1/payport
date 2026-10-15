package zw.co.payport.gateway.psp.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.PspEvent;
import zw.co.payport.gateway.psp.platform.outbox.PspEventHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Turns PSP events into customer, merchant, agent and ops messages. */
@Component
public class NotificationEventHandler implements PspEventHandler {

    private static final Set<String> TYPES = Set.of(EventTypes.WALLET_TRANSFER_COMPLETED, EventTypes.AGENT_CASH_IN,
            EventTypes.AGENT_CASH_OUT, EventTypes.SETTLEMENT_INSTRUCTION_PAID, EventTypes.SETTLEMENT_INSTRUCTION_FAILED,
            EventTypes.PAYMENT_OUTCOME_UNKNOWN, EventTypes.MERCHANT_SUSPENDED, EventTypes.DISPUTE_OPENED,
            EventTypes.RECON_BREAK_OPENED, EventTypes.FRAUD_CASE_OPENED, EventTypes.AML_ALERT_RAISED);

    private final NotificationService notify;
    private final LegacyGateway gateway;
    private final ObjectMapper om;

    @Value("${payport.psp.notify.ops-email:}")
    private String opsEmail;
    @Value("${payport.psp.notify.compliance-email:}")
    private String complianceEmail;

    public NotificationEventHandler(NotificationService notify, LegacyGateway gateway, ObjectMapper om) {
        this.notify = notify;
        this.gateway = gateway;
        this.om = om;
    }

    @Override public String name() { return "notifications"; }
    @Override public boolean supports(String eventType) { return TYPES.contains(eventType); }

    @Override
    public void handle(PspEvent e) throws Exception {
        JsonNode p = om.readTree(e.payloadJson());
        Map<String, Object> v = new HashMap<>();
        p.fieldNames().forEachRemaining(f -> v.put(f, p.path(f).asText()));
        String key = e.eventType() + ":" + e.aggregateId();
        switch (e.eventType()) {
            case EventTypes.WALLET_TRANSFER_COMPLETED -> {
                v.put("amount", NotificationService.money(p.path("amountMinor").asLong(), p.path("currency").asText()));
                v.put("fee", NotificationService.money(p.path("feeMinor").asLong() + p.path("taxMinor").asLong(), p.path("currency").asText()));
                String type = p.path("type").asText();
                notify.queue("WALLET_" + type + "_PAYER", "SMS", p.path("payerMsisdn").asText(null), v, key + ":payer");
                if (p.has("payeeMsisdn")) notify.queue("WALLET_" + type + "_PAYEE", "SMS", p.path("payeeMsisdn").asText(), v, key + ":payee");
            }
            case EventTypes.AGENT_CASH_IN, EventTypes.AGENT_CASH_OUT -> {
                v.put("amount", NotificationService.money(p.path("amountMinor").asLong(), p.path("currency").asText()));
                String base = e.eventType().equals(EventTypes.AGENT_CASH_IN) ? "CASH_IN" : "CASH_OUT";
                notify.queue(base + "_CUSTOMER", "SMS", p.path("customerMsisdn").asText(null), v, key + ":customer");
                notify.queue(base + "_AGENT", "SMS", p.path("agentMsisdn").asText(null), v, key + ":agent");
            }
            case EventTypes.SETTLEMENT_INSTRUCTION_PAID, EventTypes.SETTLEMENT_INSTRUCTION_FAILED -> {
                long merchantId = p.path("merchantId").asLong();
                gateway.merchant(merchantId).ifPresent(m -> {
                    v.put("merchantName", m.businessName());
                    if (p.has("amountMinor")) v.put("amount", NotificationService.money(p.path("amountMinor").asLong(), p.path("currency").asText()));
                    String code = e.eventType().equals(EventTypes.SETTLEMENT_INSTRUCTION_PAID) ? "SETTLEMENT_PAID" : "SETTLEMENT_FAILED";
                    notify.queue(code, "EMAIL", m.contactEmail(), v, key);
                });
            }
            case EventTypes.PAYMENT_OUTCOME_UNKNOWN -> gateway.transaction(e.aggregateId()).ifPresent(tx -> {
                v.put("amount", NotificationService.money(tx.amountCents(), tx.currency()));
                notify.queue("PAYMENT_VERIFYING", "SMS", tx.customerPhone(), v, key);
            });
            case EventTypes.MERCHANT_SUSPENDED -> gateway.merchant(p.path("merchantId").asLong()).ifPresent(m -> {
                v.put("merchantName", m.businessName());
                notify.queue("MERCHANT_SUSPENDED", "EMAIL", m.contactEmail(), v, key);
            });
            case EventTypes.DISPUTE_OPENED -> gateway.merchant(p.path("merchantId").asLong()).ifPresent(m -> {
                v.put("merchantName", m.businessName());
                v.put("amount", NotificationService.money(p.path("amountMinor").asLong(), p.path("currency").asText("USD")));
                notify.queue("DISPUTE_OPENED", "EMAIL", m.contactEmail(), v, key);
            });
            case EventTypes.RECON_BREAK_OPENED -> notify.queue("OPS_RECON_BREAK", "EMAIL", opsEmail, v, key);
            case EventTypes.FRAUD_CASE_OPENED -> notify.queue("OPS_FRAUD_CASE", "EMAIL", opsEmail, v, key);
            case EventTypes.AML_ALERT_RAISED -> notify.queue("COMPLIANCE_AML_ALERT", "EMAIL", complianceEmail, v, key);
            default -> { }
        }
    }
}
