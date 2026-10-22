package zw.co.payport.gateway.payments;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a provider's delivery failure code means, taken from the provider specifications
 * (docs/phase2/PROVIDER_ERROR_CODES.md has the source for every entry):
 *
 *  - RETRYABLE: the provider definitely did not deliver and says the cause is temporary
 *    (or is our own float), so delivering again later is safe.
 *  - UNKNOWN:   the code does not say whether the delivery happened (timeouts, "in
 *    progress", duplicates, unspecified system errors): resolve by status query, never
 *    deliver again.
 *  - FINAL:     the provider definitely did not deliver and retrying will not help
 *    (invalid number, limits, invalid meter...). Ops decide: refund.
 *
 * Providers whose specification has no error code table (Telecel, merchant checkout, ...)
 * are never retried automatically: every failure is FINAL unless the adaptor itself flagged
 * the outcome as unknown.
 */
public final class DeliveryOutcomeRules {

    public enum Verdict { RETRYABLE, UNKNOWN, FINAL }

    private record Table(Pattern codeInRaw, Set<String> retryable, Set<String> unknown) {}

    private static final Pattern ECONET_CODE = Pattern.compile("StatusCode\\s*=\\s*(\\d+)");
    private static final Pattern NETONE_CODE = Pattern.compile("\"ReplyCode\"\\s*:\\s*\"?(\\d+)");
    private static final Pattern ESOLUTIONS_CODE = Pattern.compile("\"responseCode\"\\s*:\\s*\"(\\d+)\"");

    /** Econet XML-RPC API v1.2.3, section 7.1. */
    private static final Table ECONET = new Table(ECONET_CODE,
            // 8200 External Temp Error; 905 Insufficient Credit / 8104 Insufficient Funds (PayPort's float);
            // 8209 External Out of Stock.
            Set.of("8200", "905", "8104", "8209"),
            // 990 Request Timeout; 999 General Error; 8060 Duplicate; 8061 Pending; 8201/8202 External System/Technical
            // Error; 8205/8207 External (General) Processing Error; 8206 Already Processed; 8208 In Progress;
            // 8210 External Timeout; 8211 Already Submitted. Econet offers get_transaction_status for these.
            Set.of("990", "999", "8060", "8061", "8201", "8202", "8205", "8206", "8207", "8208", "8210", "8211"));

    /** NetOne E-Top-Up Webservice 2.8.3, section 16. */
    private static final Table NETONE = new Table(NETONE_CODE,
            // 206 recharge platform down; 208 insufficient wallet balance (PayPort's float); 209 stock;
            // 210 "Unable to process ... please try again later"; 702 network webservice unavailable.
            Set.of("206", "208", "209", "210", "702"),
            // 0 pending / 1 busy (query results); 216 duplicate ("already received ... please wait"); 219 web service
            // exception; 312 request received, processing; 700 network timeout; 701 connection issue; 703/704 errors.
            Set.of("0", "1", "216", "219", "312", "700", "701", "703", "704"));

    /**
     * e-Solutions Bill Payments API v2.2, section 4 (Table 4.3): only 00 is success, 09 is in progress (resend
     * to look it up), every other code is a failure to conclude. 51 = vendor (PayPort) out of credit, so a later
     * retry is safe once the float is topped up. 94 duplicate: look up the original with a resend.
     */
    private static final Table ESOLUTIONS = new Table(ESOLUTIONS_CODE, Set.of("51"), Set.of("09", "94"));

    private static final Map<String, Table> TABLES = Map.of(
            "ECONET", ECONET,
            "NETONE", NETONE,
            "ZESA", ESOLUTIONS,
            "ZOL", ESOLUTIONS,
            "NYARADZO", ESOLUTIONS,
            "MOONLIGHT", ESOLUTIONS,
            "BULAWAYO", ESOLUTIONS);

    private DeliveryOutcomeRules() {}

    /** The provider's code, from the adaptor's explicit errorCode or its raw response. */
    public static String code(String provider, String explicitCode, String rawResponse) {
        if (explicitCode != null && !explicitCode.isBlank()) return explicitCode.trim();
        Table t = provider == null ? null : TABLES.get(provider.toUpperCase());
        if (t == null || rawResponse == null) return null;
        Matcher m = t.codeInRaw().matcher(rawResponse);
        return m.find() ? m.group(1) : null;
    }

    public static Verdict classify(String provider, String code) {
        Table t = provider == null ? null : TABLES.get(provider.toUpperCase());
        if (t == null || code == null) return Verdict.FINAL;
        if (t.unknown().contains(code)) return Verdict.UNKNOWN;
        if (t.retryable().contains(code)) return Verdict.RETRYABLE;
        return Verdict.FINAL;
    }

    public static boolean hasCodeTable(String provider) {
        return provider != null && TABLES.containsKey(provider.toUpperCase());
    }
}
