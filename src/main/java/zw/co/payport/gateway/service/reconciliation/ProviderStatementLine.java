package zw.co.payport.gateway.service.reconciliation;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One parsed line from a provider's statement/settlement export, in a
 * common shape every provider-specific parser normalises into before
 * matching against Payport's own Transaction records.
 *
 * candidateReferences deliberately holds more than one value where a
 * provider's file offers more than one plausible reference field (e.g.
 * EcoCash's statement has both a "Transfer Id" and a "Correlator"),
 * rather than guessing upfront which one Payport's own
 * paymentCorrelator/providerReference/fulfilmentReference fields
 * actually correspond to -- the matcher tries every candidate.
 */
@Getter
@Builder
public class ProviderStatementLine {
    private final List<String> candidateReferences;
    private final String phone;
    private final long amountCents;
    private final String currency;
    private final LocalDateTime timestamp;

    // Some providers' statements mix actual customer transactions with
    // periodic settlement/liquidation sweep rows (Omari's "Adjustment
    // Debit ... LIQUIDATION", EcoCash's "Automatic Liquidation" DR rows).
    // Those aren't individual transactions and must never be matched
    // against a single Payport Transaction -- this flag is how a parser
    // tells the matcher to skip a line for line-level matching.
    private final boolean isTransactionLine;

    private final String rawRow; // for audit trail / raw_provider_row storage
}
