package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantDocument;
import zw.co.payport.gateway.entity.MerchantReviewNote;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.MerchantDocumentService;
import zw.co.payport.gateway.service.MerchantService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/application-status")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_PENDING')")
public class ApplicationStatusController {

    private final MerchantService merchantService;
    private final MerchantDocumentService documentService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();

        List<MerchantReviewNote> notes = merchantService.listReviewNotes(merchant.getId());
        List<MerchantDocument> docs = documentService.listForMerchant(merchant.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", merchant.getStatus());
        body.put("applicationReference", merchant.getApplicationReference());
        body.put("businessName", merchant.getBusinessName());
        body.put("rejectionReason", merchant.getRejectionReason());
        body.put("submittedAt", merchant.getCreatedAt());
        body.put("reviewNotes", notes.stream().map(this::noteToMap).toList());
        body.put("documents", docs.stream().map(this::docToMap).toList());
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @PostMapping("/review-notes/{id}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolveNote(@PathVariable Long id, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantReviewNote note = merchantService.resolveReviewNote(merchant.getId(), id);
            return ResponseEntity.ok(ApiResponse.success(noteToMap(note), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("RESOLVE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private Map<String, Object> noteToMap(MerchantReviewNote n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("note", n.getNote());
        m.put("status", n.getStatus());
        m.put("createdAt", n.getCreatedAt());
        m.put("resolvedAt", n.getResolvedAt());
        return m;
    }

    private Map<String, Object> docToMap(MerchantDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("documentType", d.getDocumentType());
        m.put("originalFilename", d.getOriginalFilename());
        m.put("uploadedAt", d.getUploadedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
