package zw.co.payport.gateway.service.adaptor.netone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs entirely locally — no HTTP call, no VPN, no live NetOne connection.
 * This only proves the message-selection logic itself is correct; it can't
 * tell you whether NetOne's live API still behaves the way their spec says
 * it does. That still needs a real run from the server.
 */
class NetOneAirtimeAdaptorMessageTest {

    @Test
    void usesDocumentedDescription_forKnownSuccessCode() {
        // "2" is NetOne's success code per their spec (not the more common 0/1).
        String msg = NetOneAirtimeAdaptor.buildAirtimeMessage("2", "some raw text from NetOne", true);
        assertEquals("Success - Transaction completed successfully", msg);
    }

    @Test
    void usesDocumentedDescription_forKnownFailureCode() {
        String msg = NetOneAirtimeAdaptor.buildAirtimeMessage("208", "raw NetOne text", false);
        assertEquals("NetOne airtime failed: Insufficient balance - Agent wallet balance too low", msg);
    }

    @Test
    void fallsBackToRawText_forUndocumentedCode() {
        String msg = NetOneAirtimeAdaptor.buildAirtimeMessage("777", "some new code we haven't seen before", false);
        assertEquals("NetOne airtime failed: some new code we haven't seen before", msg);
    }

    @Test
    void successFlagControlsPrefix_notJustTheCode() {
        // The prefix depends on the success flag actually passed in, not
        // just the code — makes sure a caller can't accidentally get a
        // "failed" message on a real success or vice versa.
        String successMsg = NetOneAirtimeAdaptor.buildAirtimeMessage("2", "raw", true);
        String failureMsg = NetOneAirtimeAdaptor.buildAirtimeMessage("2", "raw", false);
        assertFalse(successMsg.startsWith("NetOne airtime failed"));
        assertTrue(failureMsg.startsWith("NetOne airtime failed"));
    }
}
