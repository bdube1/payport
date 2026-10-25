package zw.co.payport.gateway.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Operational = the provider answered; Down = no answer (owner, 2026-10-25). */
class ProviderHealthRulesTest {

    @Test
    void anHttpErrorIsAnAnswer() {
        assertTrue(ProviderHealthScheduler.answered(new HttpClientErrorException(HttpStatus.BAD_REQUEST)));
        assertTrue(ProviderHealthScheduler.answered(new RuntimeException("Invalid account number")));
    }

    @Test
    void noConnectionOrTimeoutIsNoAnswer() {
        assertFalse(ProviderHealthScheduler.answered(new ResourceAccessException("I/O error", new ConnectException("Connection refused"))));
        assertFalse(ProviderHealthScheduler.answered(new HttpTimeoutException("request timed out")));
        assertFalse(ProviderHealthScheduler.answered(new RuntimeException("x", new ConnectException("refused"))));
        assertFalse(ProviderHealthScheduler.answered(new RuntimeException("eSolutions is currently unavailable (HTTP 502). (non-JSON response, see server logs)")));
    }
}
