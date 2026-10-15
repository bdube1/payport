package zw.co.payport.gateway.psp.settlement;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Settlement engine for finance: build, approve (four-eyes), send, confirm with proof, recover failures. */
@RestController
@RequestMapping("/api/v1/psp/admin/settlement")
@PreAuthorize("hasAnyRole('FINANCE','ADMIN')")
public class SettlementAdminController {

    public record CreateBody(String currency, String cashAccount, List<Long> merchantIds) {}
    public record ReasonBody(String reason) {}

    private final SettlementEngine settlements;

    public SettlementAdminController(SettlementEngine settlements) {
        this.settlements = settlements;
    }

    @PostMapping("/batches")
    public ResponseEntity<ApiResponse<SettlementEngine.BatchResult>> create(@RequestBody CreateBody b, HttpServletRequest req) {
        return Api.created(settlements.createBatch(b.currency(), b.cashAccount(), b.merchantIds(), Actor.current()), req);
    }

    @GetMapping("/batches")
    public ResponseEntity<ApiResponse<List<SettlementBatch>>> batches(HttpServletRequest req) {
        return Api.ok(settlements.recentBatches(), req);
    }

    @GetMapping("/batches/{ref}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batch(@PathVariable String ref, HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batch", settlements.batch(ref));
        m.put("instructions", settlements.instructionsOf(ref));
        return Api.ok(m, req);
    }

    @PostMapping("/batches/{ref}/submit")
    public ResponseEntity<ApiResponse<SettlementBatch>> submit(@PathVariable String ref, HttpServletRequest req) {
        return Api.ok(settlements.submit(ref, Actor.current()), req);
    }

    @PostMapping("/batches/{ref}/approve")
    public ResponseEntity<ApiResponse<SettlementBatch>> approve(@PathVariable String ref, HttpServletRequest req) {
        return Api.ok(settlements.approve(ref, Actor.current()), req);
    }

    @PostMapping("/batches/{ref}/cancel")
    public ResponseEntity<ApiResponse<SettlementBatch>> cancel(@PathVariable String ref, @RequestBody ReasonBody b, HttpServletRequest req) {
        return Api.ok(settlements.cancel(ref, b.reason(), Actor.current()), req);
    }

    @PostMapping("/batches/{ref}/dispatch")
    public ResponseEntity<ApiResponse<SettlementBatch>> dispatch(@PathVariable String ref, @RequestParam String rail, HttpServletRequest req) {
        return Api.ok(settlements.dispatch(ref, rail, Actor.current()), req);
    }

    @GetMapping("/batches/{ref}/bank-file")
    public ResponseEntity<byte[]> bankFile(@PathVariable String ref) {
        byte[] file = settlements.exportBankFile(ref, Actor.current());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payport-settlement-" + ref + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(file);
    }

    @DeleteMapping("/instructions/{ref}")
    public ResponseEntity<ApiResponse<SettlementInstruction>> remove(@PathVariable String ref, @RequestBody ReasonBody b, HttpServletRequest req) {
        return Api.ok(settlements.removeInstruction(ref, b.reason(), Actor.current()), req);
    }

    /** Proof of payment: the bank/rail reference plus (for bank-file payouts) the bank's confirmation document. */
    @PostMapping(value = "/instructions/{ref}/paid", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<SettlementInstruction>> paid(@PathVariable String ref,
                                                                   @RequestParam String payoutReference,
                                                                   @RequestParam(defaultValue = "0") long bankChargeMinor,
                                                                   @RequestParam(value = "proof", required = false) MultipartFile proof,
                                                                   HttpServletRequest req) throws Exception {
        SettlementEngine.Proof p = proof == null || proof.isEmpty() ? null
                : new SettlementEngine.Proof(proof.getOriginalFilename(), proof.getContentType(), proof.getBytes());
        return Api.ok(settlements.confirmPaid(ref, payoutReference, bankChargeMinor, p, Actor.current()), req);
    }

    @PostMapping("/instructions/{ref}/failed")
    public ResponseEntity<ApiResponse<SettlementInstruction>> failed(@PathVariable String ref, @RequestBody ReasonBody b, HttpServletRequest req) {
        return Api.ok(settlements.markFailed(ref, b.reason(), Actor.current()), req);
    }

    @PostMapping("/instructions/{ref}/returned")
    public ResponseEntity<ApiResponse<SettlementInstruction>> returned(@PathVariable String ref, @RequestBody ReasonBody b, HttpServletRequest req) {
        return Api.ok(settlements.markReturned(ref, b.reason(), Actor.current()), req);
    }
}
