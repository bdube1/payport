package zw.co.payport.gateway.model.response;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.enums.TransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** R3: what an anonymous caller who only knows the reference may see. */
class TransactionResponsePublicViewTest {

    @Test
    void publicViewDropsPersonalAndDeliveryDataButKeepsWhatThePaymentScreenNeeds() {
        TransactionResponse full = TransactionResponse.builder()
                .reference("PP-WEB-20261015-000001")
                .status(TransactionStatus.COMPLETED)
                .productName("ZESA Token")
                .amountCents(2000L)
                .currency("USD")
                .customerPhone("+263771234567")
                .recipientIdentifier("37132605312")
                .providerReference("ECO-REF")
                .fulfilmentReference("FUL-REF")
                .fulfilmentData("{\"token\":\"1234-5678-9012-3456-7890\"}")
                .refundReference("RF-1")
                .timeline(List.of())
                .widgetJsUrl("https://widget")
                .paymentCorrelator("corr")
                .requiresOtpEntry(true)
                .accessToken("tok")
                .build();

        TransactionResponse pub = TransactionResponse.publicView(full);

        assertEquals("PP-WEB-20261015-000001", pub.getReference());
        assertEquals(TransactionStatus.COMPLETED, pub.getStatus());
        assertEquals(2000L, pub.getAmountCents());
        assertEquals("https://widget", pub.getWidgetJsUrl());
        assertEquals("corr", pub.getPaymentCorrelator());
        assertTrue(pub.isRequiresOtpEntry());

        assertNull(pub.getCustomerPhone());
        assertNull(pub.getRecipientIdentifier());
        assertNull(pub.getProviderReference());
        assertNull(pub.getFulfilmentReference());
        assertNull(pub.getFulfilmentData(), "the ZESA token must never be in the public view");
        assertNull(pub.getRefundReference());
        assertNull(pub.getTimeline());
        assertNull(pub.getAccessToken());
    }
}
