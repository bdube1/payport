package zw.co.payport.gateway.service.reconciliation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.ReconciliationMatch;
import zw.co.payport.gateway.entity.ReconciliationRun;
import zw.co.payport.gateway.entity.Transaction;
import zw.co.payport.gateway.repository.ReconciliationMatchRepository;
import zw.co.payport.gateway.repository.ReconciliationRunRepository;
import zw.co.payport.gateway.repository.TransactionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Matches a parsed provider statement against Payport's own COMPLETED
 * transactions for that provider and period, producing four buckets:
 *
 *   MATCHED          - found on both sides, amounts agree
 *   AMOUNT_MISMATCH  - found on both sides, amounts genuinely differ
 *   PROVIDER_ONLY    - the provider's statement has it, Payport doesn't
 *                       (Payport may be under-recording successful
 *                       payments, or the provider settled something
 *                       Payport never confirmed)
 *   PAYPORT_ONLY     - Payport recorded a completed transaction that
 *                       never shows up on the provider's own statement
 *                       (worth investigating -- was it really
 *                       confirmed correctly?)
 *
 * Two-tier matching per line: first an exact reference match against
 * every plausible Payport reference field, then -- for anything that
 * didn't match by reference -- a fuzzy fallback on amount + phone +
 * time proximity. Which tier actually finds anything varies
 * significantly by provider; this is stated openly in each parser's
 * own documentation rather than assumed uniform.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementReconciliationService {

    private final TransactionRepository transactionRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationMatchRepository matchRepository;

    // Providers matched against Transaction.paymentProviderCode (the
    // payment itself) vs Transaction.fulfilmentProviderCode (the
    // service delivered afterwards) -- see the seeded service_providers/
    // payment_methods data this was confirmed against directly.
    private static final Set<String> PAYMENT_SIDE_PROVIDERS = Set.of("ECOCASH", "ONEMONEY", "OMARI", "INNBUCKS");
    private static final Set<String> FULFILMENT_SIDE_PROVIDERS = Set.of("NETONE", "TELECEL");

    // How far apart two timestamps can be and still be considered the
    // same event for fuzzy matching. Generous because EcoCash's and
    // Omari's own sample statements carry only date-level or
    // approximate precision in places -- a tight window would create
    // false PROVIDER_ONLY/PAYPORT_ONLY splits for genuinely the same
    // transaction.
    private static final long FUZZY_TIME_WINDOW_MINUTES = 90;

    public ReconciliationRun reconcile(String provider, String uploadedFileName, String uploadedBy, List<ProviderStatementLine> parsedLines) {

        List<ProviderStatementLine> txLines = parsedLines.stream()
                .filter(ProviderStatementLine::isTransactionLine)
                .toList();

        LocalDateTime periodStart = txLines.stream().map(ProviderStatementLine::getTimestamp).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime periodEnd = txLines.stream().map(ProviderStatementLine::getTimestamp).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);

        // Pad the query window generously either side of the statement's
        // own date range -- a transaction near the edge of the period
        // may be timestamped slightly outside it depending on which
        // system's clock or timezone handling produced the boundary.
        LocalDateTime queryFrom = (periodStart != null ? periodStart : LocalDateTime.now().minusDays(1)).minusDays(1);
        LocalDateTime queryTo = (periodEnd != null ? periodEnd : LocalDateTime.now()).plusDays(1);

        List<Transaction> candidateTransactions = loadCandidates(provider, queryFrom, queryTo);
        // Mutable working pool -- entries are removed as they get
        // matched, so whatever remains at the end is genuinely unmatched
        // (PAYPORT_ONLY), not a transaction that just happened to be
        // claimed twice.
        List<Transaction> unmatchedPayport = new ArrayList<>(candidateTransactions);

        List<ReconciliationMatch> results = new ArrayList<>();
        int matchedCount = 0, mismatchCount = 0, providerOnlyCount = 0;

        for (ProviderStatementLine line : txLines) {
            Transaction match = findByExactReference(line, unmatchedPayport);
            String confidence = "EXACT_REFERENCE";

            if (match == null) {
                match = findByFuzzyMatch(line, unmatchedPayport);
                confidence = "FUZZY";
            }

            if (match == null) {
                results.add(buildMatchRow(null, "PROVIDER_ONLY", "NONE", line));
                providerOnlyCount++;
                continue;
            }

            unmatchedPayport.remove(match);
            boolean amountsAgree = match.getAmountCents() != null && match.collectCents() == line.getAmountCents();

            if (amountsAgree) {
                results.add(buildMatchRow(match, "MATCHED", confidence, line));
                matchedCount++;
            } else {
                results.add(buildMatchRow(match, "AMOUNT_MISMATCH", confidence, line));
                mismatchCount++;
            }
        }

        // Whatever's left in the pool never showed up in the statement
        // at all.
        for (Transaction tx : unmatchedPayport) {
            results.add(buildPayportOnlyRow(tx));
        }

        ReconciliationRun run = ReconciliationRun.builder()
                .provider(provider)
                .uploadedFileName(uploadedFileName)
                .uploadedBy(uploadedBy)
                .uploadedAt(LocalDateTime.now())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .totalProviderLines(txLines.size())
                .matchedCount(matchedCount)
                .amountMismatchCount(mismatchCount)
                .providerOnlyCount(providerOnlyCount)
                .payportOnlyCount(unmatchedPayport.size())
                .status("COMPLETED")
                .build();
        run = runRepository.save(run);

        for (ReconciliationMatch m : results) {
            m.setRunId(run.getId());
            matchRepository.save(m);
        }

        log.info("[RECONCILIATION] {} run complete: {} matched, {} mismatched, {} provider-only, {} payport-only",
                provider, matchedCount, mismatchCount, providerOnlyCount, unmatchedPayport.size());

        return run;
    }

    private List<Transaction> loadCandidates(String provider, LocalDateTime from, LocalDateTime to) {
        if (PAYMENT_SIDE_PROVIDERS.contains(provider)) {
            return transactionRepository.findCompletedByPaymentProviderAndPeriod(provider, from, to);
        }
        if (FULFILMENT_SIDE_PROVIDERS.contains(provider)) {
            return transactionRepository.findCompletedByFulfilmentProviderAndPeriod(provider, from, to);
        }
        log.warn("[RECONCILIATION] Unrecognised provider '{}' -- no Payport transactions will be loaded to match against", provider);
        return List.of();
    }

    private Transaction findByExactReference(ProviderStatementLine line, List<Transaction> pool) {
        if (line.getCandidateReferences() == null || line.getCandidateReferences().isEmpty()) return null;

        for (Transaction tx : pool) {
            List<String> txReferenceFields = List.of(
                    nullToEmpty(tx.getPaymentCorrelator()),
                    nullToEmpty(tx.getProviderReference()),
                    nullToEmpty(tx.getPaymentProviderTxId()),
                    nullToEmpty(tx.getFulfilmentReference()),
                    nullToEmpty(tx.getReference())
            );
            for (String candidate : line.getCandidateReferences()) {
                if (candidate == null || candidate.isBlank()) continue;
                if (txReferenceFields.stream().anyMatch(f -> f.equalsIgnoreCase(candidate))) {
                    return tx;
                }
            }
        }
        return null;
    }

    private Transaction findByFuzzyMatch(ProviderStatementLine line, List<Transaction> pool) {
        Transaction best = null;
        for (Transaction tx : pool) {
            if (tx.getAmountCents() == null || tx.collectCents() != line.getAmountCents()) continue;

            boolean phoneOk = line.getPhone() == null
                    || phoneMatches(line.getPhone(), tx.getCustomerPhone())
                    || phoneMatches(line.getPhone(), tx.getRecipientIdentifier());

            boolean timeOk = line.getTimestamp() == null || tx.getCreatedAt() == null
                    || Math.abs(Duration.between(line.getTimestamp(), tx.getCreatedAt()).toMinutes()) <= FUZZY_TIME_WINDOW_MINUTES;

            if (phoneOk && timeOk) {
                // First reasonable candidate wins -- amount+phone+time
                // agreeing on more than one open transaction in the same
                // window would be a genuine ambiguity to flag for a
                // person to look at, not silently resolve one way.
                if (best != null) {
                    log.warn("[RECONCILIATION] Ambiguous fuzzy match for provider line (amount={}, phone={}, time={}) -- multiple Payport transactions qualify, keeping the first",
                            line.getAmountCents(), line.getPhone(), line.getTimestamp());
                    break;
                }
                best = tx;
            }
        }
        return best;
    }

    private boolean phoneMatches(String statementPhone, String payportPhone) {
        if (statementPhone == null || payportPhone == null) return false;
        String a = normalisePhone(statementPhone);
        String b = normalisePhone(payportPhone);
        return !a.isEmpty() && a.equals(b);
    }

    /** Keeps only the last 9 digits so "263776764741", "0776764741",
     *  and "776764741" all compare equal regardless of which prefix
     *  convention either side happens to use. */
    private String normalisePhone(String phone) {
        String digits = phone.replaceAll("\\D", "");
        return digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
    }

    private ReconciliationMatch buildMatchRow(Transaction tx, String matchType, String confidence, ProviderStatementLine line) {
        return ReconciliationMatch.builder()
                .matchType(matchType)
                .matchConfidence(confidence)
                .transactionReference(tx != null ? tx.getReference() : null)
                .providerReference(line.getCandidateReferences() != null && !line.getCandidateReferences().isEmpty() ? line.getCandidateReferences().get(0) : null)
                .providerAmountCents(line.getAmountCents())
                .payportAmountCents(tx != null ? Long.valueOf(tx.collectCents()) : null)
                .providerPhone(line.getPhone())
                .providerTimestamp(line.getTimestamp())
                .rawProviderRow(line.getRawRow())
                .resolved(false)
                .build();
    }

    private ReconciliationMatch buildPayportOnlyRow(Transaction tx) {
        return ReconciliationMatch.builder()
                .matchType("PAYPORT_ONLY")
                .matchConfidence("NONE")
                .transactionReference(tx.getReference())
                .payportAmountCents(Long.valueOf(tx.collectCents()))
                .providerPhone(tx.getCustomerPhone())
                .providerTimestamp(tx.getCreatedAt())
                .rawProviderRow(null)
                .resolved(false)
                .build();
    }

    private String nullToEmpty(String s) { return s == null ? "" : s; }
}
