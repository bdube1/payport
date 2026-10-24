package zw.co.payport.gateway.cards;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SandboxCardIssuerTest {

    static boolean luhnValid(String pan) {
        int sum = 0;
        boolean dbl = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int d = pan.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    @Test
    void testCards_areStableValidNumbers_maskedOnRecord_fullOnlyOnReveal() {
        SandboxCardIssuer visa = new SandboxCardIssuer("VISA");
        Map<String, Object> card = Map.of("card_ref", "C00000000042", "status", "ACTIVE", "created_at", Timestamp.valueOf(LocalDateTime.of(2026, 10, 24, 9, 0)));
        CardIssuer.Issued issued = visa.issue(card);
        assertEquals("ACTIVE", issued.status());
        assertTrue(issued.maskedPan().contains("••••••"), issued.maskedPan());
        CardIssuer.Sensitive s = visa.reveal(card);
        assertEquals(16, s.pan().length());
        assertTrue(s.pan().startsWith("4"), s.pan());
        assertTrue(luhnValid(s.pan()), s.pan());
        assertEquals(s.pan().substring(12), issued.last4());
        assertEquals("10 / 29", s.expiry());
        assertTrue(s.cvv().matches("\\d{3}"));
        assertEquals(s.pan(), visa.reveal(card).pan(), "the same card always has the same test number");
        assertEquals("FROZEN", visa.setStatus(card, "FROZEN").status());
        assertTrue(new SandboxCardIssuer("ZIMSWITCH").reveal(card).pan().startsWith("627000"));
    }
}
