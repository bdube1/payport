package zw.co.payport.gateway.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These run entirely locally — no network, no VPN, no live provider needed.
 * They verify the translation LOGIC only: given a raw provider string, does
 * it map to the right customer-facing message? What actually happens when
 * a real provider is called is a separate concern this test can't cover —
 * that still needs a real end-to-end run from the server. But this at least
 * confirms the mapping rules themselves are correct and didn't regress,
 * which you can check with `mvn test` before ever touching the VPN.
 */
class CustomerMessageTranslatorTest {

    private final CustomerMessageTranslator translator = new CustomerMessageTranslator();

    @ParameterizedTest
    @CsvSource({
        "Insufficient balance in wallet, Insufficient balance. Please top up your wallet and try again.",
        "INSUFFICIENT FUNDS, Insufficient balance. Please top up your wallet and try again.",
        "Not enough credit, Insufficient balance. Please top up your wallet and try again.",
    })
    void recognisesInsufficientBalance(String raw, String expected) {
        assertEquals(expected, translator.forPaymentFailure(raw));
    }

    @Test
    void recognisesWrongPin() {
        assertEquals("The PIN entered was incorrect. Please try again.",
                translator.forPaymentFailure("Incorrect PIN entered by subscriber"));
    }

    @Test
    void recognisesCancellation() {
        assertEquals("The payment was cancelled.",
                translator.forPaymentFailure("Transaction cancelled by user"));
    }

    @Test
    void recognisesTimeout() {
        assertEquals(
            "We didn't receive a response in time. Please check your phone for a payment prompt, or try again.",
            translator.forPaymentFailure("Request timeout - no response"));
    }

    @Test
    void recognisesDuplicate() {
        assertEquals("This transaction has already been processed.",
                translator.forPaymentFailure("(94) Duplicate transaction"));
    }

    // ── The exact real-world case that started this — must never show a raw code ──
    @Test
    void unrecognisedRawProviderCodeNeverLeaksToCustomer() {
        String result = translator.forFulfilmentFailure("(8030) IN failure");
        assertFalse(result.contains("8030"), "Raw provider code must never appear in the customer-facing message");
        assertFalse(result.toLowerCase().contains("in failure"), "Raw provider text must never appear verbatim");
        assertTrue(result.toLowerCase().contains("delivering") || result.toLowerCase().contains("trouble"),
                "Should fall back to the generic fulfilment-failure message");
    }

    @Test
    void nullOrBlankMessageAlwaysFallsBackSafely() {
        assertDoesNotThrow(() -> translator.forPaymentFailure(null));
        assertDoesNotThrow(() -> translator.forPaymentFailure(""));
        assertDoesNotThrow(() -> translator.forFulfilmentFailure(null));
        assertNotNull(translator.forPaymentFailure(null));
        assertNotNull(translator.forFulfilmentFailure(""));
    }

    @Test
    void paymentFailureAndFulfilmentFailureUseDifferentGenericMessages() {
        // The customer should be told something different depending on whether
        // their money was ever taken — these must not collapse into one message.
        String paymentMsg = translator.forPaymentFailure("some totally unknown code XJ-42");
        String fulfilmentMsg = translator.forFulfilmentFailure("some totally unknown code XJ-42");
        assertNotEquals(paymentMsg, fulfilmentMsg);
        assertTrue(fulfilmentMsg.toLowerCase().contains("received") || fulfilmentMsg.toLowerCase().contains("paid")
                || fulfilmentMsg.toLowerCase().contains("delivering"),
                "Fulfilment failure message should reassure the customer their payment was received");
    }
}
