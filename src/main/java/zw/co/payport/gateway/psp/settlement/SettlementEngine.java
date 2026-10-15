package zw.co.payport.gateway.psp.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.BreakReporter;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.integration.PayoutRail;
import zw.co.payport.gateway.psp.integration.PayoutRails;
import zw.co.payport.gateway.psp.ledger.LedgerService;
import zw.co.payport.gateway.psp.ledger.domain.AccountCategory;
import zw.co.payport.gateway.psp.ledger.domain.AccountKey;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;
import zw.co.payport.gateway.psp.ledger.domain.PostingRules;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.merchant.MerchantControlService;
import zw.co.payport.gateway.psp.merchant.MerchantPayoutAccount;
import zw.co.payport.gateway.psp.merchant.MerchantPayoutAccountRepository;
import zw.co.payport.gateway.psp.merchant.MerchantProfile;
import zw.co.payport.gateway.psp.platform.audit.AuditService;
import zw.co.payport.gateway.psp.platform.outbox.EventTypes;
import zw.co.payport.gateway.psp.platform.outbox.OutboxService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Settlement engine: discharges what PayPort owes merchants. Separate from
 * reconciliation (which checks that records agree).
 *
 * Lifecycle of a batch:
 *   DRAFT --submit--> PENDING_APPROVAL --approve (different person)--> APPROVED
 *         --dispatch / export--> PROCESSING --> COMPLETED | PARTIALLY_FAILED
 *   DRAFT / PENDING_APPROVAL --cancel--> CANCELLED (all earmarks released)
 *
 * Lifecycle of an instruction and its ledger effect:
 *   RESERVED   Dr MERCHANT_AVAILABLE / Cr SETTLEMENT_PAYABLE   (funds earmarked at creation)
 *   APPROVED   -
 *   SENT       -                                               (rail accepted or in bank file)
 *   PAID       Dr SETTLEMENT_PAYABLE / Cr PLATFORM_CASH        (only with proof of payment)
 *   FAILED     Dr SETTLEMENT_PAYABLE / Cr MERCHANT_AVAILABLE   (never left; next batch retries)
 *   RETURNED   Dr PLATFORM_CASH / Cr MERCHANT_AVAILABLE        (bank bounced it; account flagged)
 *   CANCELLED  Dr SETTLEMENT_PAYABLE / Cr MERCHANT_AVAILABLE
 */
@Service
public class SettlementEngine {

    private static final Logger log = LoggerFactory.getLogger(SettlementEngine.class);
    private static final Set<String> TERMINAL = Set.of("PAID", "FAILED", "RETURNED", "CANCELLED");
    private static final Set<String> PROOF_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");

    public record Skipped(long merchantId, String reason) {}
    public record BatchResult(SettlementBatch batch, List<SettlementInstruction> instructions, List<Skipped> skipped) {}
    public record Proof(String fileName, String contentType, byte[] bytes) {}

    private final SettlementBatchRepository batches;
    private final SettlementInstructionRepository instructions;
    private final LedgerService ledger;
    private final MerchantControlService merchants;
    private final MerchantPayoutAccountRepository payoutAccounts;
    private final LegacyGateway legacy;
    private final PayoutRails rails;
    private final AuditService audit;
    private final OutboxService outbox;
    private final BreakReporter breaks;

    @Value("${payport.psp.settlement.require-funding-coverage:false}")
    private boolean requireCoverage;

    @Value("${payport.psp.settlement.proof-directory:/var/payport/uploads/settlement-proofs}")
    private String proofDirectory = "/var/payport/uploads/settlement-proofs";

    public SettlementEngine(SettlementBatchRepository batches, SettlementInstructionRepository instructions,
                             LedgerService ledger, MerchantControlService merchants, MerchantPayoutAccountRepository payoutAccounts,
                             LegacyGateway legacy, PayoutRails rails, AuditService audit, OutboxService outbox, BreakReporter breaks) {
        this.batches = batches;
        this.instructions = instructions;
        this.ledger = ledger;
        this.merchants = merchants;
        this.payoutAccounts = payoutAccounts;
        this.legacy = legacy;
        this.rails = rails;
        this.audit = audit;
        this.outbox = outbox;
        this.breaks = breaks;
    }

    // ── Building a batch ──────────────────────────────────────────────────

    /**
     * Creates a DRAFT batch paying every eligible merchant its full AVAILABLE
     * balance in this currency. Ineligible merchants are reported, not errors.
     * @param onlyMerchantIds optional subset (e.g. an ad-hoc payout); null = all merchants due today
     */
    @Transactional
    public BatchResult createBatch(String currency, String cashAccount, Collection<Long> onlyMerchantIds, Actor actor) {
        String ccy = AccountKey.normaliseCurrency(currency);
        if (cashAccount == null || cashAccount.isBlank()) throw PspException.badRequest("CASH_ACCOUNT_REQUIRED", "Name the account paying out, e.g. TRUST_CBZ_USD");
        if (!batches.findByCurrencyAndStatusIn(ccy, List.of("DRAFT", "PENDING_APPROVAL")).isEmpty()) {
            throw PspException.conflict("BATCH_ALREADY_OPEN", "Finish or cancel the open " + ccy + " batch first");
        }

        SettlementBatch b = new SettlementBatch();
        b.setBatchRef(Refs.newRef("stb"));
        b.setCurrency(ccy);
        b.setStatus("DRAFT");
        b.setCashAccount(cashAccount.toUpperCase());
        b.setCutoffAt(LocalDateTime.now());
        b.setInstructionCount(0);
        b.setTotalMinor(0);
        b.setCreatedBy(actor.id());
        batches.save(b);

        List<SettlementInstruction> created = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Collection<Long> ids = onlyMerchantIds != null ? onlyMerchantIds
                : legacy.merchantsWithStatus("ACTIVE").stream().map(LegacyGateway.MerchantSnapshot::id).toList();

        for (Long merchantId : ids) {
            try {
                MerchantProfile p = merchants.profile(merchantId);
                if (onlyMerchantIds == null && !dueToday(p.getSettlementFrequency())) {
                    skipped.add(new Skipped(merchantId, "Settlement frequency " + p.getSettlementFrequency()));
                    continue;
                }
                long available = ledger.balance(AccountKey.merchant(AccountCategory.MERCHANT_AVAILABLE, merchantId, ccy));
                if (available <= 0 || available < p.getMinPayoutMinor()) {
                    if (available > 0) skipped.add(new Skipped(merchantId, "Below minimum payout (" + available + ")"));
                    continue;
                }
                MerchantPayoutAccount acct = merchants.assertCanPayout(merchantId, ccy);
                created.add(newInstruction(b, merchantId, available, acct, actor));
            } catch (PspException e) {
                skipped.add(new Skipped(merchantId, e.code() + ": " + e.getMessage()));
            }
        }
        b.setInstructionCount(created.size());
        b.setTotalMinor(created.stream().mapToLong(SettlementInstruction::getAmountMinor).sum());
        batches.save(b);
        audit.record(actor, "SETTLEMENT_BATCH_CREATED", "SETTLEMENT_BATCH", b.getBatchRef(),
                Map.of("currency", ccy, "instructions", created.size(), "totalMinor", b.getTotalMinor(), "skipped", skipped.size()));
        log.info("[SETTLEMENT] Batch {} {}: {} instructions, total {}, {} skipped", b.getBatchRef(), ccy, created.size(), b.getTotalMinor(), skipped.size());
        return new BatchResult(b, created, skipped);
    }

    private SettlementInstruction newInstruction(SettlementBatch b, long merchantId, long amount, MerchantPayoutAccount acct, Actor actor) {
        SettlementInstruction i = new SettlementInstruction();
        i.setInstructionRef(Refs.newRef("sti"));
        i.setBatchId(b.getId());
        i.setMerchantId(merchantId);
        i.setCurrency(b.getCurrency());
        i.setAmountMinor(amount);
        i.setPayoutAccountRef(acct.getPayoutRef());
        i.setMethod(acct.getMethod());
        i.setDestinationName(acct.getAccountName());
        i.setDestinationMasked("****" + acct.getAccountNumberLast4());
        i.setBankName(acct.getBankName());
        i.setStatus("RESERVED");
        i.setBankChargeMinor(0);
        LedgerService.PostedEntry e = ledger.post(PostingRules.settlementReserved(i.getInstructionRef(), merchantId, b.getCurrency(), amount), actor.id());
        i.setReservationEntryRef(e.entryRef());
        return instructions.save(i);
    }

    private static boolean dueToday(String frequency) {
        return switch (frequency == null ? "DAILY" : frequency) {
            case "WEEKLY" -> LocalDate.now().getDayOfWeek() == DayOfWeek.MONDAY;
            case "MANUAL" -> false;
            default -> true;
        };
    }

    @Transactional
    public SettlementInstruction removeInstruction(String instructionRef, String reason, Actor actor) {
        SettlementInstruction i = instruction(instructionRef);
        SettlementBatch b = batches.findById(i.getBatchId()).orElseThrow();
        requireBatchStatus(b, "DRAFT");
        release(i, "CANCELLED", reason, actor);
        b.setInstructionCount(b.getInstructionCount() - 1);
        b.setTotalMinor(b.getTotalMinor() - i.getAmountMinor());
        batches.save(b);
        return i;
    }

    // ── Four-eyes ─────────────────────────────────────────────────────────

    @Transactional
    public SettlementBatch submit(String batchRef, Actor actor) {
        SettlementBatch b = batch(batchRef);
        requireBatchStatus(b, "DRAFT");
        if (b.getInstructionCount() == 0) throw PspException.unprocessable("EMPTY_BATCH", "Nothing to pay; cancel the batch instead");
        b.setStatus("PENDING_APPROVAL");
        b.setSubmittedBy(actor.id());
        b.setSubmittedAt(LocalDateTime.now());
        batches.save(b);
        audit.record(actor, "SETTLEMENT_BATCH_SUBMITTED", "SETTLEMENT_BATCH", batchRef, Map.of("totalMinor", b.getTotalMinor()));
        return b;
    }

    @Transactional
    public SettlementBatch approve(String batchRef, Actor actor) {
        SettlementBatch b = batch(batchRef);
        requireBatchStatus(b, "PENDING_APPROVAL");
        if (actor.id().equalsIgnoreCase(b.getCreatedBy()) || actor.id().equalsIgnoreCase(b.getSubmittedBy())) {
            throw PspException.forbidden("FOUR_EYES_VIOLATION", "A batch must be approved by someone who did not create or submit it");
        }
        if (!actor.hasAnyRole("FINANCE", "ADMIN")) throw PspException.forbidden("APPROVER_ROLE_REQUIRED", "FINANCE role required");

        long cash = ledger.balance(AccountKey.cash(b.getCashAccount(), b.getCurrency()));
        if (requireCoverage && cash < b.getTotalMinor()) {
            throw PspException.unprocessable("INSUFFICIENT_FUNDING", "Ledger shows " + cash + " in " + b.getCashAccount()
                    + ", batch needs " + b.getTotalMinor() + ". Record the provider settlements first.");
        }
        b.setStatus("APPROVED");
        b.setApprovedBy(actor.id());
        b.setApprovedAt(LocalDateTime.now());
        batches.save(b);
        for (SettlementInstruction i : instructions.findByBatchIdOrderByIdAsc(b.getId())) {
            if ("RESERVED".equals(i.getStatus())) {
                i.setStatus("APPROVED");
                instructions.save(i);
            }
        }
        audit.record(actor, "SETTLEMENT_BATCH_APPROVED", "SETTLEMENT_BATCH", batchRef,
                Map.of("totalMinor", b.getTotalMinor(), "createdBy", b.getCreatedBy(), "ledgerCashMinor", cash));
        outbox.publish(EventTypes.SETTLEMENT_BATCH_APPROVED, "SETTLEMENT_BATCH", batchRef,
                Map.of("batchRef", batchRef, "currency", b.getCurrency(), "totalMinor", b.getTotalMinor()));
        return b;
    }

    @Transactional
    public SettlementBatch cancel(String batchRef, String reason, Actor actor) {
        SettlementBatch b = batch(batchRef);
        if (!Set.of("DRAFT", "PENDING_APPROVAL").contains(b.getStatus())) {
            throw PspException.conflict("CANNOT_CANCEL", "Only DRAFT or PENDING_APPROVAL batches can be cancelled; fail individual payouts instead");
        }
        for (SettlementInstruction i : instructions.findByBatchIdOrderByIdAsc(b.getId())) {
            if (!TERMINAL.contains(i.getStatus())) release(i, "CANCELLED", reason, actor);
        }
        b.setStatus("CANCELLED");
        b.setNotes(reason);
        batches.save(b);
        audit.record(actor, "SETTLEMENT_BATCH_CANCELLED", "SETTLEMENT_BATCH", batchRef, Map.of("reason", String.valueOf(reason)));
        return b;
    }

    // ── Sending ───────────────────────────────────────────────────────────

    /** Sends every APPROVED instruction through an API rail (ZIPIT/RTGS host-to-host, wallet B2C, or SANDBOX). */
    @Transactional
    public SettlementBatch dispatch(String batchRef, String railCode, Actor actor) {
        SettlementBatch b = batch(batchRef);
        if (!Set.of("APPROVED", "PROCESSING").contains(b.getStatus())) throw PspException.conflict("NOT_APPROVED", "Batch is " + b.getStatus());
        PayoutRail rail = rails.require(railCode);
        for (SettlementInstruction i : instructions.findByBatchIdOrderByIdAsc(b.getId())) {
            if (!"APPROVED".equals(i.getStatus())) continue;
            if (!rail.supports(i.getMethod(), i.getCurrency())) continue;
            MerchantPayoutAccount acct = payoutAccounts.findByPayoutRef(i.getPayoutAccountRef()).orElseThrow();
            PayoutRail.PayoutResult r = rail.submit(new PayoutRail.PayoutRequest(i.getInstructionRef(), i.getCurrency(), i.getAmountMinor(),
                    acct.getAccountName(), acct.getBankName(), acct.getBankCode(), acct.getBranchCode(), acct.getAccountNumber(),
                    acct.getMobileNumber(), "PayPort settlement " + i.getInstructionRef()));
            i.setRail(rail.code());
            i.setRailReference(r.railReference());
            switch (r.status()) {
                case "ACCEPTED", "UNKNOWN" -> { i.setStatus("SENT"); i.setSentAt(LocalDateTime.now()); }
                case "COMPLETED" -> {
                    i.setStatus("SENT");
                    i.setSentAt(LocalDateTime.now());
                    instructions.save(i);
                    markPaidInternal(i, r.railReference(), 0, null, actor, "Rail confirmed completion: " + r.message());
                }
                default -> {
                    instructions.save(i);
                    failInternal(i, "Rail rejected: " + r.message(), actor);
                }
            }
            instructions.save(i);
        }
        b.setStatus("PROCESSING");
        batches.save(b);
        refreshBatchStatus(b);
        audit.record(actor, "SETTLEMENT_BATCH_DISPATCHED", "SETTLEMENT_BATCH", batchRef, Map.of("rail", rail.code()));
        return b;
    }

    /**
     * Bank bulk-payment file for internet-banking upload. Columns follow the
     * common Zimbabwean bank bulk format; adjust {@link BankFileFormat} per bank.
     * Account numbers are decrypted only into this file, which is hashed and audited.
     */
    @Transactional
    public byte[] exportBankFile(String batchRef, Actor actor) {
        SettlementBatch b = batch(batchRef);
        if (!Set.of("APPROVED", "PROCESSING").contains(b.getStatus())) throw PspException.conflict("NOT_APPROVED", "Batch is " + b.getStatus());
        List<String[]> rows = new ArrayList<>();
        for (SettlementInstruction i : instructions.findByBatchIdOrderByIdAsc(b.getId())) {
            if (!Set.of("APPROVED", "SENT").contains(i.getStatus())) continue;
            MerchantPayoutAccount acct = payoutAccounts.findByPayoutRef(i.getPayoutAccountRef()).orElseThrow();
            rows.add(new String[]{i.getInstructionRef(), acct.getAccountName(), String.valueOf(acct.getBankName()),
                    String.valueOf(acct.getBankCode()), String.valueOf(acct.getBranchCode()),
                    acct.getAccountNumber() != null ? acct.getAccountNumber() : String.valueOf(acct.getMobileNumber()),
                    BankFileFormat.amount(i.getAmountMinor()), i.getCurrency(), "PAYPORT " + i.getInstructionRef()});
            if ("APPROVED".equals(i.getStatus())) {
                i.setStatus("SENT");
                i.setRail("BANK_FILE");
                i.setRailReference("FILE:" + i.getInstructionRef());
                i.setSentAt(LocalDateTime.now());
                instructions.save(i);
            }
        }
        byte[] file = BankFileFormat.csv(rows).getBytes(StandardCharsets.UTF_8);
        b.setExportedFileSha256(ChainHasher.sha256(file));
        b.setExportedAt(LocalDateTime.now());
        b.setExportedBy(actor.id());
        b.setStatus("PROCESSING");
        batches.save(b);
        audit.record(actor, "SETTLEMENT_FILE_EXPORTED", "SETTLEMENT_BATCH", batchRef,
                Map.of("rows", rows.size(), "sha256", b.getExportedFileSha256()));
        return file;
    }

    // ── Outcomes ──────────────────────────────────────────────────────────

    /** Proof of payment is mandatory: a bank/rail reference and, for bank-file payouts, the bank's confirmation document. */
    @Transactional
    public SettlementInstruction confirmPaid(String instructionRef, String payoutReference, long bankChargeMinor, Proof proof, Actor actor) {
        if (payoutReference == null || payoutReference.isBlank()) throw PspException.badRequest("REFERENCE_REQUIRED", "The bank / rail payment reference is required");
        SettlementInstruction i = instruction(instructionRef);
        if (!"SENT".equals(i.getStatus())) throw PspException.conflict("NOT_SENT", "Instruction is " + i.getStatus());
        if ("BANK_FILE".equals(i.getRail()) && proof == null) {
            throw PspException.badRequest("PROOF_REQUIRED", "Upload the bank's payment confirmation for bank-file payouts");
        }
        if (proof != null) storeProof(i, proof);
        markPaidInternal(i, payoutReference, bankChargeMinor, proof, actor, "Confirmed by " + actor.id());
        refreshBatchStatus(batches.findById(i.getBatchId()).orElseThrow());
        return i;
    }

    private void markPaidInternal(SettlementInstruction i, String payoutReference, long bankChargeMinor, Proof proof, Actor actor, String note) {
        SettlementBatch b = batches.findById(i.getBatchId()).orElseThrow();
        LedgerService.PostedEntry e = ledger.post(PostingRules.settlementPaid(i.getInstructionRef(), i.getMerchantId(), i.getCurrency(),
                i.getAmountMinor(), b.getCashAccount(), Math.max(0, bankChargeMinor), i.getBankName()), actor.id());
        i.setStatus("PAID");
        i.setPaidEntryRef(e.entryRef());
        i.setPayoutReference(payoutReference);
        i.setBankChargeMinor(Math.max(0, bankChargeMinor));
        i.setPaidAt(LocalDateTime.now());
        i.setConfirmedBy(actor.id());
        instructions.save(i);
        audit.record(actor, "SETTLEMENT_PAID", "SETTLEMENT_INSTRUCTION", i.getInstructionRef(),
                Map.of("merchantId", i.getMerchantId(), "amountMinor", i.getAmountMinor(), "payoutReference", payoutReference,
                        "proofSha256", String.valueOf(i.getProofSha256()), "note", note));
        outbox.publish(EventTypes.SETTLEMENT_INSTRUCTION_PAID, "SETTLEMENT_INSTRUCTION", i.getInstructionRef(), Map.of(
                "instructionRef", i.getInstructionRef(), "merchantId", i.getMerchantId(), "currency", i.getCurrency(),
                "amountMinor", i.getAmountMinor(), "payoutReference", payoutReference, "destination", i.getDestinationMasked()));
    }

    /** The payout did not leave PayPort (rail rejected, wrong details caught before sending). Funds go back to AVAILABLE. */
    @Transactional
    public SettlementInstruction markFailed(String instructionRef, String reason, Actor actor) {
        SettlementInstruction i = instruction(instructionRef);
        if (!Set.of("APPROVED", "SENT").contains(i.getStatus())) throw PspException.conflict("CANNOT_FAIL", "Instruction is " + i.getStatus());
        failInternal(i, reason, actor);
        refreshBatchStatus(batches.findById(i.getBatchId()).orElseThrow());
        return i;
    }

    private void failInternal(SettlementInstruction i, String reason, Actor actor) {
        release(i, "FAILED", reason, actor);
        long recentFailures = instructions.findByMerchantIdAndStatusIn(i.getMerchantId(), List.of("FAILED", "RETURNED")).stream()
                .filter(x -> x.getFailedAt() != null && x.getFailedAt().isAfter(LocalDateTime.now().minusDays(14))).count();
        if (recentFailures >= 3) {
            breaks.report("SETTLEMENT", "REPEATED_PAYOUT_FAILURE", "merchant:" + i.getMerchantId(), i.getCurrency(), null, null,
                    recentFailures + " failed payouts in 14 days; check payout account " + i.getPayoutAccountRef());
        }
        outbox.publish(EventTypes.SETTLEMENT_INSTRUCTION_FAILED, "SETTLEMENT_INSTRUCTION", i.getInstructionRef(), Map.of(
                "instructionRef", i.getInstructionRef(), "merchantId", i.getMerchantId(), "reason", String.valueOf(reason)));
    }

    /** The bank paid it and later returned it (closed / wrong account). Cash is back; the payout account is flagged. */
    @Transactional
    public SettlementInstruction markReturned(String instructionRef, String reason, Actor actor) {
        SettlementInstruction i = instruction(instructionRef);
        if (!"PAID".equals(i.getStatus())) throw PspException.conflict("NOT_PAID", "Only PAID instructions can be returned");
        SettlementBatch b = batches.findById(i.getBatchId()).orElseThrow();
        LedgerService.PostedEntry e = ledger.post(PostingRules.settlementReturnedAfterPayment(i.getInstructionRef(), i.getMerchantId(),
                i.getCurrency(), i.getAmountMinor(), b.getCashAccount()), actor.id());
        i.setStatus("RETURNED");
        i.setReversalEntryRef(e.entryRef());
        i.setFailureReason(reason);
        i.setFailedAt(LocalDateTime.now());
        instructions.save(i);
        payoutAccounts.findByPayoutRef(i.getPayoutAccountRef()).ifPresent(a -> {
            a.setStatus("RETURNED_REVERIFY");
            payoutAccounts.save(a);
        });
        audit.record(actor, "SETTLEMENT_RETURNED", "SETTLEMENT_INSTRUCTION", instructionRef, Map.of("reason", String.valueOf(reason)));
        breaks.report("SETTLEMENT", "PAYOUT_RETURNED", instructionRef, i.getCurrency(), i.getAmountMinor(), null,
                "Bank returned payout: " + reason + ". Payout account " + i.getPayoutAccountRef() + " needs re-verification.");
        return i;
    }

    private void release(SettlementInstruction i, String status, String reason, Actor actor) {
        LedgerService.PostedEntry e = ledger.post(PostingRules.settlementFailed(i.getInstructionRef(), i.getMerchantId(),
                i.getCurrency(), i.getAmountMinor()), actor.id());
        i.setStatus(status);
        i.setReversalEntryRef(e.entryRef());
        i.setFailureReason(reason);
        i.setFailedAt(LocalDateTime.now());
        instructions.save(i);
        audit.record(actor, "SETTLEMENT_" + status, "SETTLEMENT_INSTRUCTION", i.getInstructionRef(), Map.of("reason", String.valueOf(reason)));
    }

    /** For API rails: asks the rail about SENT instructions and applies definitive answers. */
    @Transactional
    public int pollRails() {
        int changed = 0;
        for (SettlementInstruction i : instructions.findByStatusIn(List.of("SENT"))) {
            if (i.getRail() == null || "BANK_FILE".equals(i.getRail())) continue;
            Optional<PayoutRail> rail = rails.byCode(i.getRail());
            if (rail.isEmpty()) continue;
            PayoutRail.PayoutResult r = rail.get().status(i.getInstructionRef(), i.getRailReference());
            if ("COMPLETED".equals(r.status())) {
                markPaidInternal(i, r.railReference() != null ? r.railReference() : i.getRailReference(), 0, null,
                        Actor.system("rail-poll"), "Rail status COMPLETED: " + r.message());
                changed++;
            } else if ("REJECTED".equals(r.status())) {
                failInternal(i, "Rail rejected: " + r.message(), Actor.system("rail-poll"));
                changed++;
            }
            refreshBatchStatus(batches.findById(i.getBatchId()).orElseThrow());
        }
        return changed;
    }

    private void refreshBatchStatus(SettlementBatch b) {
        List<SettlementInstruction> all = instructions.findByBatchIdOrderByIdAsc(b.getId());
        if (all.isEmpty() || !all.stream().allMatch(i -> TERMINAL.contains(i.getStatus()))) return;
        boolean anyBad = all.stream().anyMatch(i -> Set.of("FAILED", "RETURNED").contains(i.getStatus()));
        if ("PROCESSING".equals(b.getStatus()) || "APPROVED".equals(b.getStatus())) {
            b.setStatus(anyBad ? "PARTIALLY_FAILED" : "COMPLETED");
            b.setCompletedAt(LocalDateTime.now());
            batches.save(b);
        }
    }

    private void storeProof(SettlementInstruction i, Proof proof) {
        if (proof.bytes() == null || proof.bytes().length == 0) throw PspException.badRequest("EMPTY_PROOF", "Proof file is empty");
        if (proof.bytes().length > 10 * 1024 * 1024) throw PspException.badRequest("PROOF_TOO_LARGE", "Proof must be under 10 MB");
        if (proof.contentType() == null || !PROOF_TYPES.contains(proof.contentType().toLowerCase())) {
            throw PspException.badRequest("PROOF_TYPE", "Proof must be PDF, PNG or JPEG");
        }
        String sha = ChainHasher.sha256(proof.bytes());
        String ext = proof.contentType().endsWith("pdf") ? ".pdf" : proof.contentType().endsWith("png") ? ".png" : ".jpg";
        Path dir = Paths.get(proofDirectory, i.getCurrency(), LocalDate.now().toString());
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(i.getInstructionRef() + "-" + sha.substring(0, 12) + ext);
            Files.write(file, proof.bytes());
            i.setProofStoragePath(file.toString());
        } catch (IOException e) {
            throw PspException.unavailable("PROOF_STORAGE", "Could not store proof of payment: " + e.getMessage());
        }
        i.setProofSha256(sha);
        i.setProofFileName(proof.fileName() == null ? null : proof.fileName().replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    // ── Queries ───────────────────────────────────────────────────────────

    public SettlementBatch batch(String ref) {
        return batches.findByBatchRef(ref).orElseThrow(() -> PspException.notFound("Settlement batch", ref));
    }

    public SettlementInstruction instruction(String ref) {
        return instructions.findByInstructionRef(ref).orElseThrow(() -> PspException.notFound("Settlement instruction", ref));
    }

    public List<SettlementInstruction> instructionsOf(String batchRef) {
        return instructions.findByBatchIdOrderByIdAsc(batch(batchRef).getId());
    }

    public List<SettlementBatch> recentBatches() {
        return batches.findTop100ByOrderByCreatedAtDesc();
    }

    public List<SettlementInstruction> forMerchant(long merchantId) {
        return instructions.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    private static void requireBatchStatus(SettlementBatch b, String status) {
        if (!status.equals(b.getStatus())) throw PspException.conflict("INVALID_BATCH_STATE", "Batch " + b.getBatchRef() + " is " + b.getStatus() + ", expected " + status);
    }
}
