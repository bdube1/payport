package zw.co.payport.gateway.service;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.service.PaymentStatusVerifier.Verified;
import zw.co.payport.gateway.service.adaptor.PaymentProviderAdaptor;

import static org.junit.jupiter.api.Assertions.*;

/** R4: only a definite, amount-matched provider answer may change a payment. */
class PaymentStatusVerifierTest {

    private final PaymentStatusVerifier verifier = new PaymentStatusVerifier(null, null);

    private static Transaction tx(long cents, String ccy) {
        Transaction t = new Transaction();
        t.setReference("PP-WEB-20261015-000001");
        t.setAmountCents(cents);
        t.setCurrency(ccy);
        t.setProviderReference("prov-1");
        return t;
    }

    @Test
    void unsignedCallbackProvidersRequireVerificationByDefault() {
        assertTrue(verifier.requiresVerification("ECOCASH"));
        assertTrue(verifier.requiresVerification("onemoney"));
        assertTrue(verifier.requiresVerification("ZIMSWITCH"), "R45: ZimSwitch webhooks carry no signature check");
        assertFalse(verifier.requiresVerification("OMARI"));
        assertFalse(verifier.requiresVerification(null));
    }

    @Test
    void matchingSuccessIsApplied() {
        PaymentProviderAdaptor.CallbackEvent e = verifier.toEvent(tx(500, "USD"), new Verified("SUCCESS", 500L, "USD", "ECO123", "q"), "t");
        assertNotNull(e);
        assertEquals("SUCCESS", e.getStatus());
        assertEquals(500, e.getAmountCents());
        assertEquals("ECO123", e.getProviderReference());
        assertEquals("PP-WEB-20261015-000001", e.getPayportReference());
    }

    @Test
    void successWithWrongAmountIsNotApplied() {
        assertNull(verifier.toEvent(tx(500, "USD"), new Verified("SUCCESS", 50L, "USD", "x", "q"), "t"));
    }

    @Test
    void successWithoutReportedAmountIsAppliedLikeTheProductionReconciler() {
        // Status comes from our own authenticated query; adaptors report a missing amount as none.
        PaymentProviderAdaptor.CallbackEvent e = verifier.toEvent(tx(500, "USD"), new Verified("SUCCESS", null, "USD", "x", "q"), "t");
        assertNotNull(e);
        assertEquals(0, e.getAmountCents(), "0 tells the orchestrator there is no amount to compare");
    }

    @Test
    void currencyDifferenceIsLoggedNotBlocked() {
        // EcoCash's parser defaults a missing currency to USD; blocking would stall ZiG payments.
        assertNotNull(verifier.toEvent(tx(500, "ZWG"), new Verified("SUCCESS", 500L, "USD", "x", "q"), "t"));
    }

    @Test
    void zigAndZwgAreTheSameCurrency() {
        assertNotNull(verifier.toEvent(tx(500, "ZWG"), new Verified("SUCCESS", 500L, "ZiG", "x", "q"), "t"));
    }

    @Test
    void definiteFailureIsApplied() {
        PaymentProviderAdaptor.CallbackEvent e = verifier.toEvent(tx(500, "USD"), new Verified("FAILED", null, null, null, "q"), "t");
        assertNotNull(e);
        assertEquals("FAILED", e.getStatus());
        assertEquals("prov-1", e.getProviderReference(), "falls back to the reference already on the transaction");
    }

    @Test
    void pendingOrUnknownChangesNothing() {
        assertNull(verifier.toEvent(tx(500, "USD"), new Verified("PENDING", 500L, "USD", "x", "q"), "t"));
        assertNull(verifier.toEvent(tx(500, "USD"), new Verified("UNKNOWN", null, null, null, "q"), "t"));
    }

    @Test
    void providerStatusesNormalise() {
        assertEquals("SUCCESS", PaymentStatusVerifier.normalise("completed"));
        assertEquals("FAILED", PaymentStatusVerifier.normalise("EXPIRED"));
        assertEquals("PENDING", PaymentStatusVerifier.normalise("processing"));
        assertEquals("UNKNOWN", PaymentStatusVerifier.normalise("SOMETHING_NEW"));
        assertEquals("UNKNOWN", PaymentStatusVerifier.normalise(null));
    }

    @Test
    void majorUnitsConvertExactly() {
        assertEquals(1010L, PaymentStatusVerifier.toCents(10.1));
        assertEquals(29L, PaymentStatusVerifier.toCents(0.29));
        assertEquals(1999L, PaymentStatusVerifier.toCents(19.99));
        assertEquals(123456789L, PaymentStatusVerifier.toCents(1234567.89));
        assertNull(PaymentStatusVerifier.toCents(0));
        assertNull(PaymentStatusVerifier.toCents(-1));
    }
}
