package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.MerchantDocument;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.MerchantDocumentService;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/merchants/{merchantId}/documents")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class AdminMerchantDocumentController {

    private final MerchantDocumentService documentService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(@PathVariable Long merchantId, HttpServletRequest req) {
        List<Map<String, Object>> docs = documentService.listForMerchant(merchantId).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("documentType", d.getDocumentType());
            m.put("originalFilename", d.getOriginalFilename());
            m.put("uploadedAt", d.getUploadedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(docs, requestId(req)));
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long merchantId, @PathVariable Long documentId) {
        MerchantDocument doc = documentService.getOrThrow(documentId);
        if (!doc.getMerchantId().equals(merchantId)) {
            return ResponseEntity.notFound().build();
        }
        File file = new File(doc.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        String contentType = doc.getOriginalFilename() != null && doc.getOriginalFilename().toLowerCase().endsWith(".pdf")
                ? "application/pdf" : "image/" + doc.getFilePath().substring(doc.getFilePath().lastIndexOf('.') + 1);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.getOriginalFilename() + "\"")
                .body(new FileSystemResource(file));
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
