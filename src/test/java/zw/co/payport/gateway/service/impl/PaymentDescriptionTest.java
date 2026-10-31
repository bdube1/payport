package zw.co.payport.gateway.service.impl;

import org.junit.jupiter.api.Test;
import zw.co.payport.gateway.entity.ProductType;
import zw.co.payport.gateway.entity.Transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner, 2026-10-31: the payment prompt names the product ("Econet Airtime"), not just "airtime". */
class PaymentDescriptionTest {

    static Transaction tx(String code, String name, String snapshot, String network) {
        Transaction t = new Transaction();
        t.setProductType(ProductType.builder().code(code).name(name).build());
        t.setProductName(snapshot);
        t.setRecipientNetwork(network);
        return t;
    }

    @Test
    void namesTheProductWithItsNetwork() {
        assertEquals("PayPort Econet Airtime", TransactionOrchestratorService.paymentDescription(tx("AIRTIME_ECONET", "Econet Airtime", null, "ECONET"), null));
        assertEquals("PayPort Econet Airtime", TransactionOrchestratorService.paymentDescription(tx("AIRTIME_ECONET", "Airtime", null, "ECONET"), null),
                "a product renamed to a generic word still says the network");
        assertEquals("PayPort NetOne Data Bundle", TransactionOrchestratorService.paymentDescription(tx("DATA_NETONE", "NetOne Data Bundle", "NetOne Data Bundle", "NETONE"), null));
        assertEquals("PayPort ZESA Prepaid", TransactionOrchestratorService.paymentDescription(tx("ZESA_PREPAID", "ZESA Prepaid", null, null), null));
    }

    @Test
    void merchantPaymentsNameTheMerchant() {
        assertEquals("PayPort Acme Stores", TransactionOrchestratorService.paymentDescription(tx("MERCHANT_PAYMENT", "Merchant Payment", null, null), "Acme Stores"));
    }

    @Test
    void neverLongerThanProvidersAccept() {
        String d = TransactionOrchestratorService.paymentDescription(tx("X", "A".repeat(80), null, null), null);
        assertTrue(d.length() <= 50 && d.startsWith("PayPort "), d);
    }
}
