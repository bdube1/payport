package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.config.MerchantAuthResolver;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.MerchantDocument;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.MerchantDocumentService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/documents")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MERCHANT','MERCHANT_PENDING')")
public class MerchantDocumentController {

    private final MerchantDocumentService documentService;

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam String documentType, @RequestParam("file") MultipartFile file, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantDocument doc = documentService.upload(merchant.getId(), documentType, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMap(doc), requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("UPLOAD_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        List<MerchantDocument> docs = documentService.listForMerchant(merchant.getId());
        return ResponseEntity.ok(ApiResponse.success(docs.stream().map(this::toMap).toList(), requestId(req)));
    }

    private Map<String, Object> toMap(MerchantDocument d) {
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
