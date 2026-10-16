package zw.co.payport.gateway.wallets;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.CheckoutSession;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.model.request.PayTransactionRequest;
import zw.co.payport.gateway.model.response.TransactionResponse;
import zw.co.payport.gateway.repository.TransactionRepository;
import zw.co.payport.gateway.service.CheckoutSessionService;
import zw.co.payport.gateway.service.PaymentStatusVerifier;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;
import zw.co.payport.gateway.service.impl.TransactionOrchestratorService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Paying with the wallet: the signed-in holder confirms with their PIN, the consent is recorded,
 * the payment goes through the normal orchestrator (same lifecycle, audit, fulfilment and risk
 * checks as any rail) with method WALLET, and the result is settled through the same authoritative
 * status-query path the reconciler uses. Also refunds a wallet-paid payment back to the wallet.
 */
@Slf4j
@Service
public class WalletPaymentService {

    private final WalletService wallets;
    private final PinVerifier pins;
    private final TransactionRepository transactions;
    private final TransactionOrchestratorService orchestrator;
    private final PaymentStatusVerifier verifier;
    private final CheckoutSessionService checkout;

    public WalletPaymentService(WalletService wallets, PinVerifier pins, TransactionRepository transactions, TransactionOrchestratorService orchestrator,
                                PaymentStatusVerifier verifier, CheckoutSessionService checkout) {
        this.wallets = wallets;
        this.pins = pins;
        this.transactions = transactions;
        this.orchestrator = orchestrator;
        this.verifier = verifier;
        this.checkout = checkout;
    }

    public User verifyPin(String phone, String pin) {
        return pins.verify(phone, pin);
    }

    /** Pays an initiated PayPort payment (airtime, bill, ...) from the holder's wallet in its currency. */
    public Map<String, Object> payTransaction(String phone, String reference, String pin) throws Exception {
        wallets.requireOn();
        User u = pins.verify(phone, pin);
        Transaction tx = transactions.findByReference(reference).orElseThrow(() -> new IllegalArgumentException("No such payment"));
        WalletService.Wallet w = wallets.forUser(u.getId(), tx.getCurrency())
                .orElseThrow(() -> new IllegalArgumentException("You have no " + tx.getCurrency() + " wallet"));
        if ("WALLET".equalsIgnoreCase(tx.getProductType().getCategory())) throw new IllegalArgumentException("A wallet cannot be topped up from a wallet");
        if (tx.getCustomerPhone() != null && !samePhone(tx.getCustomerPhone(), phone)) throw new IllegalArgumentException("No such payment");
        wallets.authorizePayment(w, reference, tx.getAmountCents(), tx.getCurrency(), "customer:" + phone);
        PayTransactionRequest pay = new PayTransactionRequest();
        pay.setReference(reference);
        pay.setPaymentMethodCode(WalletService.RAIL);
        pay.setCustomerPhone(tx.getCustomerPhone() != null ? tx.getCustomerPhone() : phone);
        TransactionResponse r = orchestrator.submitPayment(pay);
        settle(reference);
        return result(reference, r.getFailureReason(), w);
    }

    /** Pays a merchant's hosted checkout session from the holder's wallet. */
    public Map<String, Object> payCheckout(String phone, long sessionId, String pin, String ip) throws Exception {
        wallets.requireOn();
        User u = pins.verify(phone, pin);
        CheckoutSession s = checkout.getPublic(sessionId);
        WalletService.Wallet w = wallets.forUser(u.getId(), s.getCurrency())
                .orElseThrow(() -> new IllegalArgumentException("You have no " + s.getCurrency() + " wallet"));
        wallets.authorizePayment(w, "cs:" + sessionId, s.getAmountCents(), s.getCurrency(), "customer:" + phone);
        CheckoutSessionService.PaymentSubmissionResult r = checkout.submitPayment(sessionId, phone, null, WalletService.RAIL, ip);
        String ref = r.session().getTransactionReference();
        if (ref != null) settle(ref);
        return result(ref, r.session().getStatus(), w);
    }

    /** Applies the wallet's definite answer (the debit is synchronous, so it is known now). */
    void settle(String reference) {
        Transaction tx = transactions.findByReference(reference).orElseThrow();
        PaymentStatusVerifier.Verified v = verifier.verify(WalletService.RAIL, tx);
        PaymentProviderAdaptor.CallbackEvent e = verifier.toEvent(tx, v, "wallet payment");
        if (e != null) orchestrator.handlePaymentCallback(e);
    }

    /** Ops: refund a wallet-paid payment to the wallet and close the payment as REFUNDED. */
    public Map<String, Object> refundToWallet(String reference, String reason, String by) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required");
        Transaction tx = transactions.findByReference(reference).orElseThrow(() -> new IllegalArgumentException("No such payment"));
        TransactionStatus st = tx.getStatus();
        if (st == TransactionStatus.REFUNDED) throw new IllegalStateException(reference + " is already refunded");
        if (st != TransactionStatus.REFUND_PENDING && !st.getAllowedTransitions().contains(TransactionStatus.REFUND_PENDING)) {
            throw new IllegalStateException("A payment that is " + st + " cannot be refunded");
        }
        String entry = wallets.refundPayment(reference, by);
        TransactionResponse r = orchestrator.completeManualRefund(reference, by, entry, "WALLET", reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reference", reference);
        out.put("entryRef", entry);
        out.put("status", String.valueOf(r.getStatus()));
        return out;
    }

    static boolean samePhone(String a, String b) {
        String x = a.replaceAll("[^0-9]", ""), y = b.replaceAll("[^0-9]", "");
        return x.length() >= 9 && y.length() >= 9 && x.substring(x.length() - 9).equals(y.substring(y.length() - 9));
    }

    private Map<String, Object> result(String reference, String message, WalletService.Wallet w) {
        Transaction tx = reference == null ? null : transactions.findByReference(reference).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reference", reference);
        out.put("status", tx == null ? null : String.valueOf(tx.getStatus()));
        out.put("message", tx != null && tx.getFailureReason() != null ? tx.getFailureReason() : message);
        out.put("balanceMinor", wallets.balance(w));
        out.put("currency", w.currency());
        return out;
    }
}
