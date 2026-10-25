package zw.co.payport.gateway.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BundleParsingTest {

    @Test
    void netOneSizesAndValidityAreRead() {
        assertEquals(1024, BundleService.megabytes("1GB"));
        assertEquals(1536, BundleService.megabytes("1.5 GB"));
        assertEquals(500, BundleService.megabytes("500MB Data"));
        assertNull(BundleService.megabytes("Unlimited"));
        assertEquals(30, BundleService.days("30 Days"));
        assertEquals(7, BundleService.days("1 week"));
        assertEquals(1, BundleService.days("valid 1 day"));
        assertNull(BundleService.days(""));
    }
}
