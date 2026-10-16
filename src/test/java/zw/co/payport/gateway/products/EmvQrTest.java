package zw.co.payport.gateway.products;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmvQrTest {

    @Test
    void crcMatchesTheStandardCheckValue() {
        assertEquals("29B1", EmvQr.crc16("123456789"), "CRC-16/CCITT-FALSE check value");
    }

    @Test
    void buildParseRoundTripAndTamperDetection() {
        String q = EmvQr.build(new EmvQr.Payload(true, "cs:42", "5411", "840", 1234L, "ZW", "Alpha Store Café", "Harare", "ORD-1"));
        assertTrue(q.startsWith("000201010212"), "payload format 01, dynamic");
        EmvQr.Payload p = EmvQr.parse(q);
        assertEquals("cs:42", p.merchantRef());
        assertEquals(1234L, p.amountMinor());
        assertEquals("840", p.currencyNumeric());
        assertEquals("Alpha Store Cafe", p.merchantName(), "ASCII only");
        assertEquals("ORD-1", p.reference());
        char last = q.charAt(q.length() - 1);
        assertThrows(IllegalArgumentException.class, () -> EmvQr.parse(q.substring(0, q.length() - 1) + (last == '0' ? '1' : '0')));
        assertThrows(IllegalArgumentException.class, () -> EmvQr.parse("00020101021263041234"));
        assertThrows(IllegalArgumentException.class, () -> EmvQr.build(new EmvQr.Payload(true, "x", null, "USD", 1L, "ZW", "n", null, null)),
                "numeric currency code required");
    }

    @Test
    void ussdReferencesAreStablePerSession() {
        assertEquals(zw.co.payport.gateway.ussd.UssdService.reference("AT-1"), zw.co.payport.gateway.ussd.UssdService.reference("AT-1"));
        assertNotEquals(zw.co.payport.gateway.ussd.UssdService.reference("AT-1"), zw.co.payport.gateway.ussd.UssdService.reference("AT-2"));
    }
}
