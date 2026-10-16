package zw.co.payport.gateway.service.adaptor.zimswitch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** OPPWA result code groups (zimswitch.docs.oppwa.com/reference/resultCodes). */
class ZimSwitchResultCodeTest {

    @Test
    void groups() {
        assertEquals("SUCCESS", ZimSwitchAdaptor.classifyResultCode("000.000.000"));
        assertEquals("SUCCESS", ZimSwitchAdaptor.classifyResultCode("000.100.110"));
        assertEquals("SUCCESS", ZimSwitchAdaptor.classifyResultCode("000.400.000"), "successful, flagged for review");
        assertEquals("PENDING", ZimSwitchAdaptor.classifyResultCode("000.200.000"));
        assertEquals("PENDING", ZimSwitchAdaptor.classifyResultCode("800.400.500"));
        assertEquals("PENDING", ZimSwitchAdaptor.classifyResultCode("200.300.404"), "checkout not paid yet");
        assertEquals("UNKNOWN", ZimSwitchAdaptor.classifyResultCode("900.100.300"), "timeout, uncertain result");
        assertEquals("UNKNOWN", ZimSwitchAdaptor.classifyResultCode("100.396.104"), "uncertain status");
        assertEquals("FAILED", ZimSwitchAdaptor.classifyResultCode("900.100.301"), "timed out and reversed");
        assertEquals("FAILED", ZimSwitchAdaptor.classifyResultCode("800.100.151"), "invalid card");
        assertEquals("FAILED", ZimSwitchAdaptor.classifyResultCode("100.400.040"));
        assertEquals("FAILED", ZimSwitchAdaptor.classifyResultCode("800.400.200"), "3DS validation is a rejection, not pending");
        assertEquals("UNKNOWN", ZimSwitchAdaptor.classifyResultCode("123.456.789"), "undocumented is never FAILED");
    }
}
