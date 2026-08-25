package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Merchant;
import zw.co.payport.gateway.entity.QueryAttachment;
import zw.co.payport.gateway.entity.QueryMessage;
import zw.co.payport.gateway.entity.TransactionQuery;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.MerchantRepository;
import zw.co.payport.gateway.repository.UserRepository;
import zw.co.payport.gateway.service.QueryAttachmentService;
import zw.co.payport.gateway.service.TransactionQueryService;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/queries")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPS','ADMIN')")
public class AdminTransactionQueryController {

    private final TransactionQueryService queryService;
    private final QueryAttachmentService attachmentService;
    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;

    public record RespondBody(String status, String opsResponse) {}

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        List<TransactionQuery> queries = queryService.listAll();
        return ResponseEntity.ok(ApiResponse.success(queries.stream().map(this::toMap).toList(), requestId(req)));
    }

    @GetMapping("/by-reference/{referenceNumber}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getByReference(@PathVariable String referenceNumber, HttpServletRequest req) {
        try {
            TransactionQuery query = queryService.getByReferenceNumber(referenceNumber);
            return ResponseEntity.ok(ApiResponse.success(toMap(query), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("NOT_FOUND", e.getMessage(), requestId(req)));
        }
    }

    /** Kept for backward compatibility -- sets status and, if opsResponse is given, adds it as a thread message in one call. Prefer /messages + a separate status change going forward. */
    @PostMapping("/{id}/respond")
    public ResponseEntity<ApiResponse<Map<String, Object>>> respond(@PathVariable Long id, @RequestBody RespondBody body, HttpServletRequest req) {
        try {
            User staff = currentStaffUser();
            if (body.opsResponse() != null && !body.opsResponse().isBlank()) {
                queryService.addMessage(id, "STAFF", staff.getId(), staff.getFullName(), body.opsResponse());
            }
            TransactionQuery query = body.status() != null ? queryService.setStatus(id, body.status()) : queryService.getOrThrow(id);
            return ResponseEntity.ok(ApiResponse.success(toMap(query), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("RESPOND_FAILED", e.getMessage(), requestId(req)));
        }
    }

    public record MessageBody(String message) {}

    @PostMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addMessage(@PathVariable Long id, @RequestBody MessageBody body, HttpServletRequest req) {
        try {
            User staff = currentStaffUser();
            QueryMessage msg = queryService.addMessage(id, "STAFF", staff.getId(), staff.getFullName(), body.message());
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toMessageMap(msg), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("MESSAGE_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listMessages(@PathVariable Long id, HttpServletRequest req) {
        List<QueryMessage> messages = queryService.listMessages(id);
        return ResponseEntity.ok(ApiResponse.success(messages.stream().map(this::toMessageMap).toList(), requestId(req)));
    }

    public record StatusBody(String status) {}

    @PostMapping("/{id}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setStatus(@PathVariable Long id, @RequestBody StatusBody body, HttpServletRequest req) {
        try {
            TransactionQuery query = queryService.setStatus(id, body.status());
            return ResponseEntity.ok(ApiResponse.success(toMap(query), requestId(req)));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("STATUS_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @PostMapping(value = "/{id}/attachments", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadAttachment(
            @PathVariable Long id, @RequestParam("file") org.springframework.web.multipart.MultipartFile file, HttpServletRequest req) {
        try {
            User staff = currentStaffUser();
            QueryAttachment attachment = attachmentService.upload(id, file, "STAFF", staff.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", attachment.getId());
            m.put("originalFilename", attachment.getOriginalFilename());
            m.put("fileSizeBytes", attachment.getFileSizeBytes());
            m.put("createdAt", attachment.getCreatedAt());
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(m, requestId(req)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("UPLOAD_FAILED", e.getMessage(), requestId(req)));
        }
    }

    @GetMapping("/{id}/attachments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAttachments(@PathVariable Long id, HttpServletRequest req) {
        List<QueryAttachment> attachments = attachmentService.listForQuery(id);
        List<Map<String, Object>> body = attachments.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("originalFilename", a.getOriginalFilename());
            m.put("fileSizeBytes", a.getFileSizeBytes());
            m.put("uploadedByType", a.getUploadedByType());
            m.put("createdAt", a.getCreatedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(ApiResponse.success(body, requestId(req)));
    }

    @GetMapping("/{id}/attachments/{attachmentId}/download")
    public ResponseEntity<org.springframework.core.io.FileSystemResource> downloadAttachment(
            @PathVariable Long id, @PathVariable Long attachmentId) {
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
        m.put("merchantId", q.getMerchantId());
        m.put("merchantName", merchantRepository.findById(q.getMerchantId()).map(Merchant::getBusinessName).orElse("Unknown"));
        m.put("subject", q.getSubject());
        m.put("message", q.getMessage());
        m.put("status", q.getStatus());
        m.put("createdAt", q.getCreatedAt());
        m.put("resolvedAt", q.getResolvedAt());
        return m;
    }

    private User currentStaffUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String phone = auth.getName();
        return userRepository.findByPhone(phone).orElseThrow(() -> new RuntimeException("Staff user not found"));
    }

    private String requestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
