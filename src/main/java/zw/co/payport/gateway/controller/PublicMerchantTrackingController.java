package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantDocument;
import zw.co.payport.gateway.entity.MerchantReviewNote;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.MerchantReviewNoteRepository;
import zw.co.payport.gateway.service.MerchantDocumentService;
import zw.co.payport.gateway.service.MerchantService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant-portal/track")
@RequiredArgsConstructor
public class PublicMerchantTrackingController {

    private final MerchantRepository merchantRepository;
    private final MerchantReviewNoteRepository reviewNoteRepository;
    private final MerchantDocumentService documentService;
    private final MerchantService merchantService;

    @GetMapping("/{reference}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> track(@PathVariable String reference, HttpServletRequest req) {
        Merchant merchant = merchantRepository.findByApplicationReference(reference).orElse(null);
        if (merchant == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "No application found with that reference", requestId(req)));
        }

        List<MerchantReviewNote> notes = reviewNoteRepository.findByMerchantIdOrderByCreatedAtDesc(merchant.getId());
        List<MerchantDocument> docs = documentService.listForMerchant(merchant.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationReference", merchant.getApplicationReference());
        body.put("businessName", merchant.getBusinessName());
        body.put("status", merchant.getStatus());
        body.put("rejectionReason", merchant.getRejectionReason());
        body.put("submittedAt", merchant.getCreatedAt());
        body.put("reviewNotes", notes.stream().map(this::toNoteMap).toList());
        body.put("documents", docs.stream().map(this::toDocMap).toList());
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @PostMapping(value = "/{reference}/documents", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadDocument(
            @PathVariable String reference,
            @RequestParam("documentType") String documentType,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest req) {
        try {
            Merchant merchant = requireMerchant(reference);
            MerchantDocument doc = documentService.upload(merchant.getId(), documentType, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toDocMap(doc), requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("UPLOAD_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping("/{reference}/review-notes/{noteId}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolveNote(
            @PathVariable String reference, @PathVariable Long noteId, HttpServletRequest req) {
        try {
            Merchant merchant = requireMerchant(reference);
            MerchantReviewNote note = merchantService.resolveReviewNote(merchant.getId(), noteId);
            return ResponseEntity.ok(ApiResponse.success(toNoteMap(note), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("RESOLVE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private Merchant requireMerchant(String reference) {
        return merchantRepository.findByApplicationReference(reference)
                .orElseThrow(() -> new RuntimeException("No application found with that reference"));
    }

    private Map<String, Object> toNoteMap(MerchantReviewNote n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("note", n.getNote());
        m.put("status", n.getStatus());
        m.put("createdAt", n.getCreatedAt());
        m.put("resolvedAt", n.getResolvedAt());
        return m;
    }

    private Map<String, Object> toDocMap(MerchantDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("documentType", d.getDocumentType());
        m.put("originalFilename", d.getOriginalFilename());
        m.put("createdAt", d.getUploadedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
