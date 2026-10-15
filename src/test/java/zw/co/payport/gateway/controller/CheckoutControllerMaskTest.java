package zw.co.payport.gateway.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** R3: the public hosted-checkout view must not reveal the payer's phone number. */
class CheckoutControllerMaskTest {

    @Test
    void masksAllButCountryCodeAndLastThreeDigits() {
        assertEquals("+263******567", CheckoutController.maskPhone("+263771234567"));
        assertEquals("0771***567", CheckoutController.maskPhone("0771234567"));
    }

    @Test
    void handlesEmptyAndShortValues() {
        assertNull(CheckoutController.maskPhone(null));
        assertNull(CheckoutController.maskPhone(" "));
        assertEquals("*****", CheckoutController.maskPhone("12345"));
    }
}
