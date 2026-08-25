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
import zw.co.payport.gateway.entity.MerchantUser;
import zw.co.payport.gateway.entity.QueryAttachment;
import zw.co.payport.gateway.entity.QueryMessage;
import zw.co.payport.gateway.entity.TransactionQuery;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.service.QueryAttachmentService;
import zw.co.payport.gateway.service.TransactionQueryService;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchant/queries")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MERCHANT')")
public class TransactionQueryController {

    private final TransactionQueryService queryService;
    private final QueryAttachmentService attachmentService;

    public record RaiseBody(Long checkoutSessionId, String subject, String message) {}

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> raise(@RequestBody RaiseBody body, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantUser self = requireSelf();
            TransactionQuery query = queryService.raise(merchant.getId(), self.getId(), self.getFullName(),
                    new TransactionQueryService.RaiseQueryRequest(body.checkoutSessionId(), body.subject(), body.message()));
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMap(query), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("QUERY_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listMine(HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        List<TransactionQuery> queries = queryService.listForMerchant(merchant.getId());
        return ResponseEntity.ok(ApiResponse.success(queries.stream().map(this::toMap).toList(), requestId(req)));
    }

    @GetMapping("/by-session/{sessionId}")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listForSession(@PathVariable Long sessionId, HttpServletRequest req) {
        List<TransactionQuery> queries = queryService.listForSession(sessionId);
        return ResponseEntity.ok(ApiResponse.success(queries.stream().map(this::toMap).toList(), requestId(req)));
    }

    @GetMapping("/by-reference/{referenceNumber}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getByReference(@PathVariable String referenceNumber, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            TransactionQuery query = queryService.getByReferenceNumber(referenceNumber);
            if (!query.getMerchantId().equals(merchant.getId())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", "No query found with reference: " + referenceNumber, requestId(req)));
            }
            return ResponseEntity.ok(ApiResponse.success(toMap(query), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", e.getMessage(), requestId(req)));
        }
    }

    public record MessageBody(String message) {}

    @PostMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addMessage(@PathVariable Long id, @RequestBody MessageBody body, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantUser self = requireSelf();
            TransactionQuery query = queryService.getOrThrow(id);
            if (!query.getMerchantId().equals(merchant.getId())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", "Query not found: " + id, requestId(req)));
            }
            QueryMessage msg = queryService.addMessage(id, "MERCHANT_USER", self.getId(), self.getFullName(), body.message());
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMessageMap(msg), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("MESSAGE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listMessages(@PathVariable Long id, HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        TransactionQuery query = queryService.getOrThrow(id);
        if (!query.getMerchantId().equals(merchant.getId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", "Query not found: " + id, requestId(req)));
        }
        List<QueryMessage> messages = queryService.listMessages(id);
        return ResponseEntity.ok(ApiResponse.success(messages.stream().map(this::toMessageMap).toList(), requestId(req)));
    }

    public record ReopenBody(String message) {}

    @PostMapping("/{id}/reopen")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reopen(@PathVariable Long id, @RequestBody(required = false) ReopenBody body, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            MerchantUser self = requireSelf();
            TransactionQuery query = queryService.reopen(id, merchant.getId(), self.getId(), self.getFullName(), body != null ? body.message() : null);
            return ResponseEntity.ok(ApiResponse.success(toMap(query), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("REOPEN_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping(value = "/{id}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadAttachment(
            @PathVariable Long id, @RequestParam("file") MultipartFile file, HttpServletRequest req) {
        try {
            Merchant merchant = MerchantAuthResolver.currentMerchant();
            TransactionQuery query = queryService.getOrThrow(id);
            if (!query.getMerchantId().equals(merchant.getId())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", "Query not found: " + id, requestId(req)));
            }
            MerchantUser self = MerchantAuthResolver.currentMerchantUserOrNull();
            Long uploaderId = self != null ? self.getId() : 0L;
            QueryAttachment attachment = attachmentService.upload(id, file, "MERCHANT_USER", uploaderId);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toAttachmentMap(attachment), requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("UPLOAD_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAttachments(@PathVariable Long id, HttpServletRequest req) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        TransactionQuery query = queryService.getOrThrow(id);
        if (!query.getMerchantId().equals(merchant.getId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", "Query not found: " + id, requestId(req)));
        }
        List<QueryAttachment> attachments = attachmentService.listForQuery(id);
        return ResponseEntity.ok(ApiResponse.success(attachments.stream().map(this::toAttachmentMap).toList(), requestId(req)));
    }

    @GetMapping("/{id}/attachments/{attachmentId}/download")
    public ResponseEntity<org.springframework.core.io.FileSystemResource> downloadAttachment(
            @PathVariable Long id, @PathVariable Long attachmentId) {
        Merchant merchant = MerchantAuthResolver.currentMerchant();
        TransactionQuery query = queryService.getOrThrow(id);
        if (!query.getMerchantId().equals(merchant.getId())) {
            return ResponseEntity.notFound().build();
        }
        QueryAttachment attachment = attachmentService.getOrThrow(attachmentId);
        if (!attachment.getQueryId().equals(id)) {
            return ResponseEntity.notFound().build();
        }
        File file = new File(attachment.getStoredFilename());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + attachment.getOriginalFilename() + "\"")
                .body(new org.springframework.core.io.FileSystemResource(file));
    }

    private MerchantUser requireSelf() {
        MerchantUser self = MerchantAuthResolver.currentMerchantUserOrNull();
        if (self == null) {
            throw new RuntimeException("Only a logged-in dashboard user can do this, not an API key");
        }
        return self;
    }

    private Map<String, Object> toAttachmentMap(QueryAttachment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("originalFilename", a.getOriginalFilename());
        m.put("fileSizeBytes", a.getFileSizeBytes());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private Map<String, Object> toMessageMap(QueryMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("senderType", m.getSenderType());
        map.put("senderName", m.getSenderName());
        map.put("eventType", m.getEventType());
        map.put("message", m.getMessage());
        map.put("createdAt", m.getCreatedAt());
        return map;
    }

    private Map<String, Object> toMap(TransactionQuery q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.getId());
        m.put("referenceNumber", q.getReferenceNumber());
        m.put("checkoutSessionId", q.getCheckoutSessionId());
        m.put("subject", q.getSubject());
        m.put("message", q.getMessage());
        m.put("status", q.getStatus());
        m.put("createdAt", q.getCreatedAt());
        m.put("resolvedAt", q.getResolvedAt());
        return m;
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
