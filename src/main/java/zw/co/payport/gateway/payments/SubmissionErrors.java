package zw.co.payport.gateway.payments;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Classifies an error from a provider call: could the request have reached the provider?
 *
 * Only errors that prove the request never left PayPort count as NOT_SENT (safe to treat as
 * a definite failure). Everything else - read timeouts, broken connections, 5xx responses,
 * unparseable responses - is UNKNOWN: the provider may have accepted and charged the
 * customer, so the payment must stay pending and be resolved by a status query, never by
 * failing it or sending another request.
 */
public final class SubmissionErrors {

    public enum Kind { NOT_SENT, UNKNOWN }

    private SubmissionErrors() {}

    public static Kind classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException || t instanceof ConnectException || t instanceof NoRouteToHostException) {
                return Kind.NOT_SENT;
            }
            if (t instanceof SocketTimeoutException) {
                String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
                // "connect timed out" = TCP connection never established; "Read timed out" = request may be processed.
                return m.contains("connect") ? Kind.NOT_SENT : Kind.UNKNOWN;
            }
            String name = t.getClass().getName();
            // 4xx from the provider: it received and REJECTED the request (definite).
            if (name.equals("org.springframework.web.client.HttpClientErrorException")
                    || name.startsWith("org.springframework.web.client.HttpClientErrorException$")) {
                return Kind.NOT_SENT;
            }
        }
        return Kind.UNKNOWN;
    }

    public static boolean isOutcomeUnknown(Throwable error) {
        return classify(error) == Kind.UNKNOWN;
    }
}
