package zw.co.payport.gateway.ussd;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UssdMaskTest {

    @Test
    void pinsAreReplacedInTheKeptPath() {
        assertEquals("#*8*5*#*#", UssdService.mask("1357*8*5*1357*2468", Set.of(0, 3, 4)));
        assertEquals("1*2", UssdService.mask("1*2", Set.of()));
        assertEquals("", UssdService.mask("", Set.of(0)));
    }

    /** transactions.reference is VARCHAR(24): the session references must fit (the 25-27 character ones failed). */
    @Test
    void sessionReferencesFitTheTransactionReferenceColumn() {
        for (String ch : new String[] { "USSD", "WHATSAPP" }) {
            String r = UssdService.reference(ch, "wa:263772699555:0123456789abcdef");
            org.junit.jupiter.api.Assertions.assertTrue(r.length() <= 24, r);
            org.junit.jupiter.api.Assertions.assertTrue(r.matches("PP-(USD|WA)-\\d{8}-[0-9A-F]{8}"), r);
            assertEquals(r, UssdService.reference(ch, "wa:263772699555:0123456789abcdef"), "a retry gets the same reference");
        }
    }
}
