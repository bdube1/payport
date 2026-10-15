package zw.co.payport.gateway.psp.common;

/**
 * Raises a reconciliation break (an exception needing a human) from any
 * module without depending on the recon module directly. Implemented by
 * recon.ReconBreakService. Reporting the same (source, type, reference)
 * twice is a no-op, so callers can report on every run.
 */
public interface BreakReporter {

    void report(String source, String breakType, String reference, String currency,
                Long expectedMinor, Long actualMinor, String details);
}
