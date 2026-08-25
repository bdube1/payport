package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantReviewNote;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.MerchantReviewNoteRepository;
import zw.co.payport.gateway.service.MerchantService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/application")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_PENDING')")
public class MerchantApplicationStatusController {

    private final MerchantService merchantService;
    private final MerchantReviewNoteRepository reviewNoteRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        List<MerchantReviewNote> notes = reviewNoteRepository.findByMerchantIdOrderByCreatedAtDesc(merchant.getId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", merchant.getStatus());
        body.put("businessName", merchant.getBusinessName());
        body.put("rejectionReason", merchant.getRejectionReason());
        body.put("reviewNotes", notes.stream().map(this::toMap).toList());
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @PostMapping("/review-notes/{id}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolveNote(@PathVariable Long id, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantReviewNote note = merchantService.resolveReviewNote(merchant.getId(), id);
            return ResponseEntity.ok(ApiResponse.success(toMap(note), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("RESOLVE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    private Map<String, Object> toMap(MerchantReviewNote n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("note", n.getNote());
        m.put("status", n.getStatus());
        m.put("createdAt", n.getCreatedAt());
        m.put("resolvedAt", n.getResolvedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
