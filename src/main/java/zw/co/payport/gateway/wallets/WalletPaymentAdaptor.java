package zw.co.payport.gateway.wallets;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.channels.ChannelUnavailableException;
import zw.co.payport.gateway.payments.PaymentAttemptStore;
import zw.co.payport.gateway.platform.Web;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import java.util.List;
import java.util.Map;

/**
 * "WALLET" as a payment method. Internal and synchronous: submit debits the wallet at once, but only
 * when the holder confirmed this payment with their PIN (wallet_payment_authorizations, written by
 * the authenticated /api/v1/wallet/pay endpoint). A guest submitting WALLET through the public pay
 * endpoint is refused with nothing taken. There are no external callbacks: the status query reads
 * the wallet operation, so the normal reconciliation path settles the payment even if the caller
 * disappears after the debit.
 */
@Slf4j
@Component
public class WalletPaymentAdaptor implements PaymentProviderAdaptor {

    private final WalletService wallets;
    private final JdbcTemplate jdbc;

    public WalletPaymentAdaptor(WalletService wallets, JdbcTemplate jdbc) {
        this.wallets = wallets;
        this.jdbc = jdbc;
    }

    @Override
    public String getProviderCode() {
        return WalletService.RAIL;
    }

    @Override
    public PaymentSubmitResult submitPayment(PaymentRequest r) {
        String intent = PaymentAttemptStore.intentReference(r.getPayportReference());
        try {
            String entry = wallets.debitForPayment(intent, r.getAmountCents(), r.getCurrency());
            return PaymentSubmitResult.builder().submitted(true).providerReference(entry).message("Paid from wallet").rawResponse(entry).build();
        } catch (Web.InsufficientFundsException | SecurityException | IllegalArgumentException | IllegalStateException | ChannelUnavailableException e) {
            return PaymentSubmitResult.builder().submitted(false).message(e.getMessage() + ". No money was taken.").rawResponse(e.getMessage()).build();
        }
    }

    private String checkoutKey(String reference) {
        List<Long> s = jdbc.queryForList("SELECT id FROM checkout_sessions WHERE transaction_reference = ?", Long.class, PaymentAttemptStore.intentReference(reference));
        return s.isEmpty() ? "-" : "cs:" + s.get(0);
    }

    @Override
    public CallbackValidationResult validateCallback(Map<String, String> headers, String rawBody) {
        return CallbackValidationResult.builder().valid(false).failureReason("wallet payments have no external callbacks").build();
    }

    @Override
    public CallbackEvent parseCallback(String rawBody) {
        throw new UnsupportedOperationException("wallet payments have no external callbacks");
    }

    @Override
    public PaymentStatusResult queryStatus(String reference) {
        List<Map<String, Object>> op = jdbc.queryForList("SELECT reference, entry_ref, amount_minor FROM wallet_operations WHERE kind = 'PAYMENT' AND (entry_ref = ? OR reference = ?) LIMIT 1",
                reference, PaymentAttemptStore.intentReference(reference));
        if (op.isEmpty()) {
            // A debit needs a live PIN authorisation; once that has expired (or never existed) no debit can happen.
            Integer live = jdbc.queryForObject("SELECT COUNT(*) FROM wallet_payment_authorizations WHERE reference IN (?, ?) AND expires_at > NOW(6)",
                    Integer.class, PaymentAttemptStore.intentReference(reference), checkoutKey(reference));
            return PaymentStatusResult.builder().status(live != null && live > 0 ? "PENDING" : "FAILED")
                    .message("No wallet debit for " + reference).build();
        }
        return PaymentStatusResult.builder().status("SUCCESS").amountCents(((Number) op.get(0).get("amount_minor")).longValue())
                .providerReference((String) op.get(0).get("entry_ref")).message("Paid from wallet").build();
    }
}
