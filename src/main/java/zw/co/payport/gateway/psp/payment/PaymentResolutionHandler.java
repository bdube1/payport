package zw.co.payport.gateway.psp.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.legacy.LegacyPayments;
import zw.co.payport.gateway.psp.platform.approval.ApprovalHandler;

import java.time.LocalDateTime;

/**
 * Four-eyes manual resolution of a payment the rails could not confirm.
 * The requester must attach evidence (provider statement line, provider
 * support ticket, bank statement reference); a FINANCE approver executes it.
 */
@Component
public class PaymentResolutionHandler implements ApprovalHandler {

    public static final String ACTION = "PAYMENT_RESOLVE";

    private final LegacyGateway gateway;
    private final LegacyPayments payments;
    private final PaymentCheckRepository checks;
    private final ObjectMapper om;

    public PaymentResolutionHandler(LegacyGateway gateway, LegacyPayments payments, PaymentCheckRepository checks, ObjectMapper om) {
        this.gateway = gateway;
        this.payments = payments;
        this.checks = checks;
        this.om = om;
    }

    @Override public String actionType() { return ACTION; }
    @Override public String requiredRole() { return "FINANCE"; }

    @Override
    public void validate(String reference, String payloadJson) {
        JsonNode n = parse(payloadJson);
        String outcome = n.path("outcome").asText();
        if (!"SUCCESS".equals(outcome) && !"FAILED".equals(outcome)) throw PspException.badRequest("INVALID_OUTCOME", "outcome must be SUCCESS or FAILED");
        if (n.path("evidence").asText("").length() < 10) throw PspException.badRequest("EVIDENCE_REQUIRED", "Describe the evidence (statement line, ticket number...)");
        LegacyGateway.TransactionSnapshot tx = gateway.transaction(reference).orElseThrow(() -> PspException.notFound("Transaction", reference));
        if (tx.status() != TransactionStatus.PAYMENT_PENDING && tx.status() != TransactionStatus.PAYMENT_TIMEOUT) {
            throw PspException.conflict("NOT_UNRESOLVED", "Payment is already " + tx.status());
        }
    }

    @Override
    public String execute(String reference, String payloadJson, String requestedBy, String approvedBy) {
        validate(reference, payloadJson);
        JsonNode n = parse(payloadJson);
        LegacyGateway.TransactionSnapshot tx = gateway.transaction(reference).orElseThrow();
        String outcome = n.path("outcome").asText();
        String evidence = n.path("evidence").asText();
        String raw = "Manual resolution by " + requestedBy + ", approved by " + approvedBy + ". Evidence: " + evidence;
        payments.applyOutcome(reference, outcome, n.path("providerReference").asText(null), "SUCCESS".equals(outcome) ? tx.amountCents() : 0,
                tx.currency(), raw, "FAILED".equals(outcome) ? "Your payment could not be completed." : null);
        checks.findByReference(reference).ifPresent(c -> {
            c.setStatus("RESOLVED");
            c.setResolvedOutcome(outcome);
            c.setResolvedBy(approvedBy);
            c.setResolvedAt(LocalDateTime.now());
            checks.save(c);
        });
        return reference + " resolved as " + outcome;
    }

    private JsonNode parse(String json) {
        try { return om.readTree(json); } catch (Exception e) { throw PspException.badRequest("INVALID_PAYLOAD", "Payload is not valid JSON"); }
    }
}
