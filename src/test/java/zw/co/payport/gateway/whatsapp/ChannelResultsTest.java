package zw.co.payport.gateway.whatsapp;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.payments.TransactionOutcomeService.Outcome;
import zw.co.payport.gateway.payments.TransactionOutcomeService.Result;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owner, 2026-11-01: real-time payments; the chat says successful or failed, never "being delivered". */
class ChannelResultsTest {

    static final Map<String, Object> TX = Map.of("product_name", "Econet Airtime", "amount_cents", 50L, "charge_cents", 0L, "currency", "USD",
            "recipient_identifier", "0772699555", "payment_provider_code", "ECOCASH");

    @Test
    void declinedShowsTheProvidersReason_andThatNoMoneyWasTaken() {
        String m = ChannelResults.message(new Result("PP-WA-1", Outcome.FAILED, "Insufficient funds", TransactionStatus.PAYMENT_FAILED, 1), TX);
        assertTrue(m.startsWith("Payment failed: Econet Airtime USD 0.50 for 0772699555") && m.contains("Insufficient funds") && m.contains("No money was taken") && m.contains("PP-WA-1"), m);
    }

    @Test
    void purchaseFailedAfterPayment_saysFailedAndRefunded_notNoMoneyTaken() {
        String m = ChannelResults.message(new Result("R", Outcome.FAILED, "Invalid subscriber", TransactionStatus.FULFILMENT_FAILED, 1), TX);
        assertTrue(m.startsWith("Failed: Econet Airtime") && m.contains("Invalid subscriber") && m.contains("Your EcoCash payment will be refunded"), m);
        assertFalse(m.contains("No money was taken"), m);
    }

    @Test
    void notApprovedInTime_orNoAnswer_isAFailure() {
        assertTrue(ChannelResults.message(new Result("R", Outcome.TIMED_OUT, null, TransactionStatus.PAYMENT_TIMEOUT, 1), TX).startsWith("Payment failed"));
        assertTrue(ChannelResults.message(new Result("R", Outcome.NO_RESPONSE, null, TransactionStatus.PAYMENT_TIMEOUT, 1), TX).startsWith("Payment failed"));
    }

    @Test
    void neverSaysBeingDelivered() {
        for (TransactionStatus s : ChannelResults.DELIVERING) {
            String m = ChannelResults.message(new Result("R", Outcome.NO_RESPONSE, null, s, 1), TX);
            assertFalse(m.toLowerCase().contains("being delivered") || m.toLowerCase().contains("payment received"), m);
            assertTrue(m.contains("Do not pay again"), m);
        }
    }

    @Test
    void successIsLeftToTheDeliveryMessage() {
        assertNull(ChannelResults.message(new Result("R", Outcome.SUCCEEDED, null, TransactionStatus.COMPLETED, 1), TX));
    }
}
