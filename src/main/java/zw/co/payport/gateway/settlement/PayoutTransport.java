package zw.co.payport.gateway.settlement;

import java.util.List;

/**
 * Delivers payout instruction files to the sponsor bank and collects its results. The bank's
 * channel (SFTP, API, portal upload) is not known yet; the default implementation leaves the file
 * for an operator to upload (ManualPayoutTransport). A bank-specific bean replaces it.
 */
public interface PayoutTransport {

    /** True if send() actually delivers to the bank (false = an operator uploads the file). */
    boolean automatic();

    /** Delivers the file. Must be idempotent for the same batchRef + sha256 (re-sends are deduplicated by the bank via instruction refs). */
    void send(SettlementBatchService.PayoutFile file);

    /** Results available from the bank since the last call, with the evidence file's name and hash. Empty if none or manual. */
    List<BankResultFile> fetchResults();

    record BankResultFile(String name, String sha256, List<SettlementBatchService.PayoutResult> results) {}
}
