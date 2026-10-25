package zw.co.payport.gateway.service.adaptor.telecel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TelecelBalanceParsingTest {

    @Test
    void readsTheFirstFigureInCents() {
        // The live getBalance answer from Telecel's TPR service.
        assertEquals(29.32, TelecelSoapClient.balanceAmount("0002932C0000000000000002840C000000000000"), 0.0001);
        assertEquals(-5.00, TelecelSoapClient.balanceAmount("0000500D000000000000"), 0.0001);
    }

    @Test
    void readsPlainNumbersAndRejectsGarbage() {
        assertEquals(29.32, TelecelSoapClient.balanceAmount("29.32"), 0.0001);
        assertNull(TelecelSoapClient.balanceAmount("abc"));
        assertNull(TelecelSoapClient.balanceAmount(null));
    }
}
