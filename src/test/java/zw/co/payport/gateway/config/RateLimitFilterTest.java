package zw.co.payport.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/** Pilot security review: a caller cannot choose its own address, and codes cannot be enumerated one bucket each. */
class RateLimitFilterTest {

    static MockHttpServletRequest req(String remote, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void forwardedForIsBelievedOnlyFromOurProxy_andOnlyItsLastEntry() {
        assertEquals("41.1.2.3", RateLimitFilter.clientIp(req("41.1.2.3", "8.8.8.8")), "from the internet the header is ignored");
        assertEquals("41.1.2.3", RateLimitFilter.clientIp(req("127.0.0.1", "8.8.8.8, 41.1.2.3")), "the entry our proxy wrote, not the caller's");
        assertEquals("41.1.2.3", RateLimitFilter.clientIp(req("10.0.0.5", "41.1.2.3, 10.0.0.9")), "behind a load balancer");
        assertEquals("127.0.0.1", RateLimitFilter.clientIp(req("127.0.0.1", null)));
    }

    @Test
    void codesShareOneBucket() {
        assertEquals(RateLimitFilter.bucketOf("/api/v1/merchants/by-code/000001"), RateLimitFilter.bucketOf("/api/v1/merchants/by-code/000002"));
        assertEquals(RateLimitFilter.bucketOf("/api/v1/billers/ZESA/lookup"), RateLimitFilter.bucketOf("/api/v1/billers/NYARADZO/lookup"));
        assertEquals(RateLimitFilter.bucketOf("/api/v1/checkout/1/omari-otp"), RateLimitFilter.bucketOf("/api/v1/transactions/PP-1/confirm-omari-otp"));
        assertEquals("/api/v1/auth/login", RateLimitFilter.bucketOf("/api/v1/auth/login"));
    }
}
