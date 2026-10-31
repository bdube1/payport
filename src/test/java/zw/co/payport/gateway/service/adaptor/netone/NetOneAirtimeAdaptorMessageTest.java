package zw.co.payport.gateway.service.adaptor.netone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The customer sees NetOne's own wording; Payport's catalogued description is only a
 * fallback when NetOne sends no text (owner decision, Phase 2 review).
 */
class NetOneAirtimeAdaptorMessageTest {

    @Test
    void passesNetOnesOwnWordingThrough() {
        assertEquals("Your recharge request was too much or too little.",
                NetOneAirtimeAdaptor.buildAirtimeMessage("222", "Your recharge request was too much or too little.", false));
        assertEquals("Recharge to 0712 of $1 was successful.",
                NetOneAirtimeAdaptor.buildAirtimeMessage("2", "Recharge to 0712 of $1 was successful.", true));
    }

    @Test
    void fallsBackToTheDocumentedDescriptionOnlyWhenNetOneSendsNoText() {
        assertEquals("Insufficient balance - Agent wallet balance too low", NetOneAirtimeAdaptor.buildAirtimeMessage("208", " ", false));
        assertEquals("NetOne could not complete the recharge", NetOneAirtimeAdaptor.buildAirtimeMessage("777", null, false));
    }
}
