package zw.co.payport.gateway.iso20022;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.model.response.ApiResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Finance: the bank's ISO 20022 messages (camt.053 statements, pain.002 payout status reports). */
@RestController
@RequestMapping("/api/v1/admin/finance/iso20022")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
public class BankMessageController {

    private final BankMessageService service;

    public BankMessageController(BankMessageService service) {
        this.service = service;
    }

    @GetMapping("/schemas")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> schemas(HttpServletRequest req) {
        return ok(service.schemaStatus(), req);
    }

    @PostMapping("/camt053")
    public ResponseEntity<ApiResponse<Map<String, Object>>> statement(@RequestParam("file") MultipartFile file, HttpServletRequest req) throws Exception {
        try {
            return ok(service.importStatement(file.getBytes(), actor()), req);
        } catch (Iso20022Schemas.InvalidMessageException | IllegalArgumentException | IllegalStateException e) {
            return bad("STATEMENT_REFUSED", e, req);
        }
    }

    @PostMapping("/pain002")
    public ResponseEntity<ApiResponse<Map<String, Object>>> statusReport(@RequestParam("file") MultipartFile file, HttpServletRequest req) throws Exception {
        try {
            return ok(service.applyPaymentStatusReport(file.getBytes(), actor()), req);
        } catch (Iso20022Schemas.InvalidMessageException | IllegalArgumentException e) {
            return bad("STATUS_REPORT_REFUSED", e, req);
        }
    }

    @GetMapping("/statements")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> statements(HttpServletRequest req) {
        return ok(service.statements(), req);
    }

    @GetMapping("/statements/{id}/entries")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> entries(@PathVariable long id, HttpServletRequest req) {
        return ok(service.entries(id), req);
    }

    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> messages(@RequestParam(required = false) String type, HttpServletRequest req) {
        return ok(service.messages(type), req);
    }

    private static String actor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getName() == null) throw new IllegalStateException("no authenticated user");
        return a.getName();
    }

    private static String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body, HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(body, rid(req)));
    }

    private static <T> ResponseEntity<ApiResponse<T>> bad(String code, Exception e, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(ApiResponse.error(code, e.getMessage(), rid(req)));
    }
}
