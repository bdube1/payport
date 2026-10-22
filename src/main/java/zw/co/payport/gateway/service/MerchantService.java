package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantReviewNote;
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.model.request.MerchantRequests;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.MerchantReviewNoteRepository;
import zw.co.payport.gateway.repository.MerchantUserRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Phase 0 of the Merchants Module: signup lands a business in PENDING
 * status with no ability to process anything, an ops staff member reviews
 * and approves/rejects it, and approval generates the merchant's first
 * (test-mode) API key. See the Merchants Module design document for the
 * full phased plan this is Phase 0 of.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantUserRepository merchantUserRepository;
    private final ApiKeyService apiKeyService;
    private final MerchantRiskScoringService riskScoringService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.risk.RiskEngine riskEngine;
    private final MerchantReviewNoteRepository reviewNoteRepository;
    private final EmailService emailService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final java.security.SecureRandom secureRandom = new java.security.SecureRandom();

    /**
     * Public signup. Creates the Merchant (PENDING) and its first
     * MerchantUser (OWNER) together -- a merchant can't exist without at
     * least one person able to eventually log into it.
     */
    public Merchant signup(MerchantRequests.SignupRequest req) {
        String registrationNumber = req.getRegistrationNumber().trim();
        if (merchantRepository.existsByRegistrationNumber(registrationNumber)) {
            throw new RuntimeException("A merchant with this registration number already exists");
        }
        String email = req.getContactEmail().trim().toLowerCase();
        if (merchantUserRepository.existsByEmail(email)) {
            throw new RuntimeException("An account with this email already exists");
        }

        validatePasswordPolicy(req.getPassword());

        MerchantRiskScoringService.RiskScoreResult risk = riskScoringService.score(
                req.getBusinessName(), registrationNumber, email, req.getContactPhone(), req.getBusinessCategory());

        Merchant merchant = Merchant.builder()
                .applicationReference(generateApplicationReference())
                .businessName(req.getBusinessName().trim())
                .registrationNumber(registrationNumber)
                .businessCategory(req.getBusinessCategory())
                .contactEmail(email)
                .contactPhone(req.getContactPhone().trim())
                .status("PENDING")
                .riskScore(risk.score())
                .riskFlags(String.join(", ", risk.flags()))
                .build();
        merchant = merchantRepository.save(merchant);

        MerchantUser owner = MerchantUser.builder()
                .merchantId(merchant.getId())
                .fullName(req.getOwnerFullName().trim())
                .email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .role("OWNER")
                .status("ACTIVE")
                .passwordSetAt(LocalDateTime.now())
                .build();
        merchantUserRepository.save(owner);

        // Phase 6: screen the business and its owner against the screening lists (a hit opens a compliance case).
        if (riskEngine != null) riskEngine.screenMerchant(merchant.getId(), merchant.getBusinessName(), owner.getFullName(), registrationNumber);

        emailService.sendMerchantApplicationReceived(email, merchant.getBusinessName(), merchant.getApplicationReference());

        log.info("[MERCHANT] Signup received: {} (reg: {}), reference {}, status PENDING",
                merchant.getBusinessName(), registrationNumber, merchant.getApplicationReference());
        return merchant;
    }

    private String generateApplicationReference() {
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int suffix = 1000 + secureRandom.nextInt(9000);
        String candidate = "MER-" + datePart + "-" + suffix;
        while (merchantRepository.findByApplicationReference(candidate).isPresent()) {
            suffix = 1000 + secureRandom.nextInt(9000);
            candidate = "MER-" + datePart + "-" + suffix;
        }
        return candidate;
    }

    public List<Merchant> listPending() {
        return merchantRepository.findByStatus("PENDING");
    }

    public List<Merchant> listAll() {
        return merchantRepository.findAll();
    }

    public record ApprovalResult(Merchant merchant, ApiKeyService.GeneratedKeyPair testKeyPair) {}

    /**
     * Ops approves a pending merchant. Generates the merchant's first
     * TEST-mode API key immediately -- LIVE keys are a later step, gated
     * on payout account verification (not built in Phase 0).
     *
     * Returns the raw secret key alongside the merchant record because
     * this is the only moment it will ever exist in plaintext -- only its
     * hash is persisted. The controller must surface it in the response
     * now; there is no "forgot my secret key" recovery, only rotation.
     */
    public ApprovalResult approve(Long merchantId, Long approvedByStaffUserId) {
        Merchant merchant = getOrThrow(merchantId);
        Set<String> approvableFrom = Set.of("PENDING", "UNDER_REVIEW", "UPDATE_REQUIRED");
        if (!approvableFrom.contains(merchant.getStatus())) {
            throw new RuntimeException("Cannot approve a merchant in status: " + merchant.getStatus());
        }
        merchant.setStatus("ACTIVE");
        merchant.setApprovedAt(LocalDateTime.now());
        merchant.setApprovedBy(approvedByStaffUserId);
        merchant = merchantRepository.save(merchant);

        ApiKeyService.GeneratedKeyPair keyPair = apiKeyService.generateKeyPair(merchant.getId(), "TEST", "Default test key");

        log.info("[MERCHANT] Approved: {} (id {}) by staff user {}", merchant.getBusinessName(), merchant.getId(), approvedByStaffUserId);
        return new ApprovalResult(merchant, keyPair);
    }

    public Merchant reject(Long merchantId, String reason) {
        Merchant merchant = getOrThrow(merchantId);
        Set<String> rejectableFrom = Set.of("PENDING", "UNDER_REVIEW", "UPDATE_REQUIRED");
        if (!rejectableFrom.contains(merchant.getStatus())) {
            throw new RuntimeException("Cannot reject a merchant in status: " + merchant.getStatus());
        }
        merchant.setStatus("REJECTED");
        merchant.setRejectionReason(reason);
        merchant = merchantRepository.save(merchant);
        log.info("[MERCHANT] Rejected: {} (id {}): {}", merchant.getBusinessName(), merchant.getId(), reason);
        return merchant;
    }

    public Merchant suspend(Long merchantId) {
        Merchant merchant = getOrThrow(merchantId);
        if (!"ACTIVE".equals(merchant.getStatus())) {
            throw new RuntimeException("Only an ACTIVE merchant can be suspended (current status: " + merchant.getStatus() + ")");
        }
        merchant.setStatus("SUSPENDED");
        merchant = merchantRepository.save(merchant);
        log.info("[MERCHANT] Suspended: {} (id {})", merchant.getBusinessName(), merchant.getId());
        return merchant;
    }

    public Merchant reactivate(Long merchantId) {
        Merchant merchant = getOrThrow(merchantId);
        if (!"SUSPENDED".equals(merchant.getStatus())) {
            throw new RuntimeException("Only a SUSPENDED merchant can be reactivated (current status: " + merchant.getStatus() + ")");
        }
        merchant.setStatus("ACTIVE");
        merchant = merchantRepository.save(merchant);
        log.info("[MERCHANT] Reactivated: {} (id {})", merchant.getBusinessName(), merchant.getId());
        return merchant;
    }

    /** PENDING -> UNDER_REVIEW -- ops has started actively looking at the application. Purely informational for the applicant's tracking page; doesn't require anything from them. */
    public Merchant startReview(Long merchantId) {
        Merchant merchant = getOrThrow(merchantId);
        if (!"PENDING".equals(merchant.getStatus())) {
            throw new RuntimeException("Only a PENDING application can move to UNDER_REVIEW (current status: " + merchant.getStatus() + ")");
        }
        merchant.setStatus("UNDER_REVIEW");
        merchant = merchantRepository.save(merchant);
        log.info("[MERCHANT] Under review: {} (id {})", merchant.getBusinessName(), merchant.getId());
        return merchant;
    }

    /**
     * Ops asks the applicant for something -- a missing document, a
     * clarification -- without rejecting outright. Moves the merchant to
     * UPDATE_REQUIRED, records the note so the applicant can see exactly
     * what's being asked on their tracking page, and best-effort emails
     * them too.
     */
    public MerchantReviewNote requestMoreInfo(Long merchantId, Long staffUserId, String note) {
        Merchant merchant = getOrThrow(merchantId);
        Set<String> requestableFrom = Set.of("PENDING", "UNDER_REVIEW", "UPDATE_REQUIRED");
        if (!requestableFrom.contains(merchant.getStatus())) {
            throw new RuntimeException("Cannot request more information for a merchant in status: " + merchant.getStatus());
        }
        if (note == null || note.isBlank()) {
            throw new RuntimeException("A note explaining what's needed is required");
        }

        merchant.setStatus("UPDATE_REQUIRED");
        merchantRepository.save(merchant);

        MerchantReviewNote reviewNote = MerchantReviewNote.builder()
                .merchantId(merchantId)
                .note(note.trim())
                .requestedByStaffId(staffUserId)
                .status("OPEN")
                .build();
        reviewNoteRepository.save(reviewNote);

        emailService.sendMerchantInfoRequest(merchant.getContactEmail(), merchant.getBusinessName(), note.trim());

        log.info("[MERCHANT] Info requested for {} (id {}) by staff {}: {}", merchant.getBusinessName(), merchantId, staffUserId, note);
        return reviewNote;
    }

    /**
     * The applicant has addressed a note (uploaded the missing document,
     * replied with a clarification, whatever it was) -- marks the note
     * resolved and moves the merchant back to UNDER_REVIEW so ops knows
     * to take another look. Callable by either the merchant themselves
     * (self-service) or ops (on the applicant's behalf).
     */
    public MerchantReviewNote resolveReviewNote(Long merchantId, Long noteId) {
        MerchantReviewNote note = reviewNoteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("Review note not found: " + noteId));
        if (!note.getMerchantId().equals(merchantId)) {
            throw new RuntimeException("Review note not found: " + noteId);
        }
        note.setStatus("RESOLVED");
        note.setResolvedAt(LocalDateTime.now());
        reviewNoteRepository.save(note);

        boolean anyStillOpen = !reviewNoteRepository.findByMerchantIdAndStatus(merchantId, "OPEN").isEmpty();
        if (!anyStillOpen) {
            Merchant merchant = getOrThrow(merchantId);
            if ("UPDATE_REQUIRED".equals(merchant.getStatus())) {
                merchant.setStatus("UNDER_REVIEW");
                merchantRepository.save(merchant);
            }
        }

        log.info("[MERCHANT] Review note {} resolved for merchant {}", noteId, merchantId);
        return note;
    }

    public List<MerchantReviewNote> listReviewNotes(Long merchantId) {
        return reviewNoteRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    public record UpdateDetailsRequest(String businessName, String businessCategory, String contactEmail, String contactPhone) {}

    /** Ops-only correction of merchant-provided details -- e.g. a typo in the business name, or a phone number that changed. Does not touch status, risk score, or commission. */
    public Merchant updateDetails(Long merchantId, UpdateDetailsRequest req) {
        Merchant merchant = getOrThrow(merchantId);
        if (req.businessName() != null && !req.businessName().isBlank()) {
            merchant.setBusinessName(req.businessName().trim());
        }
        if (req.businessCategory() != null) {
            merchant.setBusinessCategory(req.businessCategory().isBlank() ? null : req.businessCategory().trim());
        }
        if (req.contactEmail() != null && !req.contactEmail().isBlank()) {
            merchant.setContactEmail(req.contactEmail().trim().toLowerCase());
        }
        if (req.contactPhone() != null && !req.contactPhone().isBlank()) {
            merchant.setContactPhone(req.contactPhone().trim());
        }
        merchant = merchantRepository.save(merchant);
        log.info("[MERCHANT] Details updated by ops: {} (id {})", merchant.getBusinessName(), merchant.getId());
        return merchant;
    }

    private Merchant getOrThrow(Long id) {
        return merchantRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Merchant not found: " + id));
    }

    /** Same policy already enforced for staff passwords -- kept identical on purpose, not reinvented. */
    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < 8) {
            throw new RuntimeException("Password must be at least 8 characters long");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new RuntimeException("Password must contain at least one uppercase letter");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new RuntimeException("Password must contain at least one lowercase letter");
        }
        if (!password.matches(".*\\d.*")) {
            throw new RuntimeException("Password must contain at least one digit");
        }
        if (!password.matches(".*[^A-Za-z0-9].*")) {
            throw new RuntimeException("Password must contain at least one special character");
        }
    }
}
