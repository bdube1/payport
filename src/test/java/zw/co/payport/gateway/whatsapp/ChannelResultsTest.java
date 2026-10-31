package zw.co.payport.gateway.whatsapp;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.enums.TransactionStatus;
import zw.co.payport.gateway.payments.TransactionOutcomeService.Outcome;
import zw.co.payport.gateway.payments.TransactionOutcomeService.Result;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owner, 2026-10-31: WhatsApp gives the payment's answer like the app does, with the same reasons. */
class ChannelResultsTest {

    static final Map<String, Object> TX = Map.of("product_name", "Econet Airtime", "amount_cents", 500L, "charge_cents", 0L, "currency", "USD");

    @Test
    void declinedShowsTheProvidersReason_andThatNoMoneyWasTaken() {
        String m = ChannelResults.message(new Result("PP-WA-1", Outcome.FAILED, "Insufficient funds", TransactionStatus.PAYMENT_FAILED, 1), TX);
        assertTrue(m.contains("Econet Airtime USD 5.00") && m.contains("Insufficient funds") && m.contains("No money was taken") && m.contains("PP-WA-1"), m);
    }

    @Test
    void notApprovedInTime_noAnswer_andDelivering() {
        assertTrue(ChannelResults.message(new Result("R", Outcome.TIMED_OUT, null, TransactionStatus.PAYMENT_TIMEOUT, 1), TX).contains("not approved in time"));
        assertTrue(ChannelResults.message(new Result("R", Outcome.NO_RESPONSE, null, TransactionStatus.PAYMENT_PENDING, 1), TX).contains("Do not pay again"));
        assertTrue(ChannelResults.message(new Result("R", Outcome.PROCESSING, null, TransactionStatus.PAYMENT_CONFIRMED, 1), TX).contains("Payment received"));
    }

    @Test
    void successIsLeftToTheDeliveryMessage() {
        assertNull(ChannelResults.message(new Result("R", Outcome.SUCCEEDED, null, TransactionStatus.COMPLETED, 1), TX));
    }
}
