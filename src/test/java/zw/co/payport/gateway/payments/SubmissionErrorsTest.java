package zw.co.payport.gateway.payments;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;
import static zw.co.payport.gateway.payments.SubmissionErrors.Kind.*;

/** Timeout is not failure: only errors proving the request never reached the provider are NOT_SENT. */
class SubmissionErrorsTest {

    @Test
    void requestNeverLeftPayport() {
        assertEquals(NOT_SENT, SubmissionErrors.classify(new ConnectException("Connection refused")));
        assertEquals(NOT_SENT, SubmissionErrors.classify(new UnknownHostException("api.provider")));
        assertEquals(NOT_SENT, SubmissionErrors.classify(new SocketTimeoutException("Connect timed out")));
        assertEquals(NOT_SENT, SubmissionErrors.classify(new ResourceAccessException("I/O error", new ConnectException("refused"))));
    }

    @Test
    void providerReceivedAndRejected() {
        assertEquals(NOT_SENT, SubmissionErrors.classify(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null)));
    }

    @Test
    void mayHaveReachedTheProvider() {
        assertEquals(UNKNOWN, SubmissionErrors.classify(new SocketTimeoutException("Read timed out")));
        assertEquals(UNKNOWN, SubmissionErrors.classify(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"))));
        assertEquals(UNKNOWN, SubmissionErrors.classify(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad gateway", null, null, null)));
        assertEquals(UNKNOWN, SubmissionErrors.classify(new IOException("Connection reset")));
        assertEquals(UNKNOWN, SubmissionErrors.classify(new IllegalStateException("could not parse response")));
        assertEquals(UNKNOWN, SubmissionErrors.classify(null));
    }
}
