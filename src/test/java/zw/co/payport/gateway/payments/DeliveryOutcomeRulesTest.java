package zw.co.payport.gateway.payments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.payments.DeliveryOutcomeRules.Verdict.*;

/** Retry rules taken from the provider specifications (docs/phase2/PROVIDER_ERROR_CODES.md). */
class DeliveryOutcomeRulesTest {

    @Test
    void econetCodesFromTheXmlRpcSpec() {
        assertEquals("8200", DeliveryOutcomeRules.code("ECONET", null, "{Status=0, StatusCode=8200, Description=(8200) External Temp Error}"));
        assertEquals(RETRYABLE, DeliveryOutcomeRules.classify("ECONET", "8200"));
        assertEquals(RETRYABLE, DeliveryOutcomeRules.classify("ECONET", "905"));     // insufficient credit: PayPort's float
        assertEquals(UNKNOWN, DeliveryOutcomeRules.classify("ECONET", "990"));       // request timeout
        assertEquals(UNKNOWN, DeliveryOutcomeRules.classify("ECONET", "8210"));      // external timeout
        assertEquals(UNKNOWN, DeliveryOutcomeRules.classify("ECONET", "8060"));      // duplicate
        assertEquals(FINAL, DeliveryOutcomeRules.classify("ECONET", "8002"));        // user does not exist
        assertEquals(FINAL, DeliveryOutcomeRules.classify("ECONET", "8032"));        // value too high
    }

    @Test
    void netOneReplyCodesFromTheETopUpSpec() {
        assertEquals("210", DeliveryOutcomeRules.code("NETONE", null, "{\"ReplyCode\":210,\"ReplyMsg\":\"Unable to process\"}"));
        assertEquals("217", DeliveryOutcomeRules.code("NETONE", null, "{\"ReplyCode\":\"217\"}"));
        assertEquals(RETRYABLE, DeliveryOutcomeRules.classify("NETONE", "210"));
        assertEquals(RETRYABLE, DeliveryOutcomeRules.classify("NETONE", "208"));
        assertEquals(UNKNOWN, DeliveryOutcomeRules.classify("NETONE", "700"));
        assertEquals(UNKNOWN, DeliveryOutcomeRules.classify("NETONE", "216"));
        assertEquals(FINAL, DeliveryOutcomeRules.classify("NETONE", "217"));
        assertEquals(FINAL, DeliveryOutcomeRules.classify("NETONE", "222"));
    }

    @Test
    void eSolutionsResponseCodesFromTheBillPaymentsSpec() {
        assertEquals("09", DeliveryOutcomeRules.code("ZESA", null, "{\"responseCode\":\"09\",\"narrative\":\"in progress\"}"));
        assertEquals(UNKNOWN, DeliveryOutcomeRules.classify("ZESA", "09"));
        assertEquals(RETRYABLE, DeliveryOutcomeRules.classify("ZOL", "51"));
        assertEquals(FINAL, DeliveryOutcomeRules.classify("NYARADZO", "14"));
        assertEquals(FINAL, DeliveryOutcomeRules.classify("MOONLIGHT", "68"), "68 must be treated like any other failure (spec 4.2.2)");
    }

    @Test
    void providersWithoutACodeTableAreNeverRetried() {
        assertFalse(DeliveryOutcomeRules.hasCodeTable("TELECEL"));
        assertEquals(FINAL, DeliveryOutcomeRules.classify("TELECEL", "500"));
        assertEquals(FINAL, DeliveryOutcomeRules.classify("MERCHANT", null));
        assertNull(DeliveryOutcomeRules.code("TELECEL", null, "anything"));
    }

    @Test
    void attemptReferencesMapBackToThePayment() {
        assertEquals("PP-WEB-20261016-000123", PaymentAttemptStore.intentReference("PP-WEB-20261016-000123-2"));
        assertEquals("PP-WEB-20261016-000123", PaymentAttemptStore.intentReference("PP-WEB-20261016-000123"));
        assertEquals("PP-WEB-20261016-000123", PaymentAttemptStore.attemptReference("PP-WEB-20261016-000123", 1));
        assertEquals("PP-WEB-20261016-000123-3", PaymentAttemptStore.attemptReference("PP-WEB-20261016-000123", 3));
        assertEquals("CHK-ABC", PaymentAttemptStore.intentReference("CHK-ABC"));
    }
}
