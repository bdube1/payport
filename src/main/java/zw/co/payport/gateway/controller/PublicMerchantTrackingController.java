package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.MerchantRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public, unauthenticated application tracking by reference.
 *
 * Phase 0.5 containment (R2): application references (MER-yyyyMMdd-NNNN, 9,000 per
 * day) can be guessed, so this endpoint must not reveal anything an applicant would
 * not want a stranger to see, and must not accept any change. It now returns only
 * the application status. Everything else - business details, review notes,
 * documents, uploads and resolving notes - is available only to the applicant's own
 * logged-in account (role MERCHANT_PENDING / MERCHANT) at:
 *
 *   GET  /api/v1/merchant/application
 *   POST /api/v1/merchant/application/review-notes/{id}/resolve
 *   GET  /api/v1/merchant/documents
 *   POST /api/v1/merchant/documents
 */
@RestController
@RequestMapping("/api/v1/merchant-portal/track")
@RequiredArgsConstructor
public class PublicMerchantTrackingController {

    static final String LOGIN_MESSAGE =
            "Log in to the merchant portal with the email and password you registered with to view details, "
                    + "respond to review notes and upload documents.";

    private final MerchantRepository merchantRepository;

    @GetMapping("/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> track(@PathVariable String reference, HttpServletRequest req) {
        Merchant merchant = merchantRepository.findByApplicationReference(reference).orElse(null);
        if (merchant == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "No application found with that reference", requestId(req)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationReference", merchant.getApplicationReference());
        body.put("status", merchant.getStatus());
        body.put("message", LOGIN_MESSAGE);
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    /** Removed: anyone who guessed a reference could add documents to another business's application. */
    @PostMapping("/{reference}/documents")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadDocument(@PathVariable String reference, HttpServletRequest req) {
        return gone(req);
    }

    /** Removed: anyone who guessed a reference could mark another business's review notes as resolved. */
    @PostMapping("/{reference}/review-notes/{noteId}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolveNote(@PathVariable String reference, @PathVariable Long noteId,
                                                                        HttpServletRequest req) {
        return gone(req);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> gone(HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiResponse.error("LOGIN_REQUIRED", LOGIN_MESSAGE, requestId(req)));
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
