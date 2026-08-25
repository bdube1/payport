package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.Beneficiary;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.BeneficiaryRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Beneficiary Controller
 *
 * GET    /api/v1/beneficiaries       - List the logged-in user's beneficiaries
 * POST   /api/v1/beneficiaries       - Create a beneficiary
 * PUT    /api/v1/beneficiaries/{id}  - Update a beneficiary
 * DELETE /api/v1/beneficiaries/{id}  - Delete a beneficiary
 *
 * Every endpoint here is scoped to the authenticated user's phone number,
 * same pattern as TransactionController's /my-history.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/beneficiaries")
@RequiredArgsConstructor
public class BeneficiaryController {

    private final BeneficiaryRepository beneficiaryRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Beneficiary>>> list(HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        String phone = requireAuthenticatedPhone();
        if (phone == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required to view beneficiaries", requestId));
        }
        List<Beneficiary> list = beneficiaryRepository.findByUserPhoneOrderByCreatedAtDesc(phone);
        return ResponseEntity.ok(ApiResponse.success(list, requestId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Beneficiary>> create(
            @RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        String phone = requireAuthenticatedPhone();
        if (phone == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required to save beneficiaries", requestId));
        }

        String type = body.getOrDefault("type", "PHONE");
        if (!"PHONE".equals(type) && !"BILLS".equals(type)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("VALIDATION_ERROR", "type must be PHONE or BILLS", requestId));
        }
        if (body.get("name") == null || body.get("name").isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("VALIDATION_ERROR", "name is required", requestId));
        }

        Beneficiary b = Beneficiary.builder()
                .userPhone(phone)
                .type(type)
                .name(body.get("name").trim())
                .phone(body.get("phone"))
                .networkName(body.get("networkName"))
                .networkColor(body.get("networkColor"))
                .billerCode(body.get("billerCode"))
                .billerName(body.get("billerName"))
                .accountRef(body.get("accountRef"))
                .build();

        Beneficiary saved = beneficiaryRepository.save(b);
        log.info("[Beneficiary] created id={} type={} user={}", saved.getId(), type, phone);
        return ResponseEntity.ok(ApiResponse.success(saved, requestId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Beneficiary>> update(
            @PathVariable Long id, @RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        String phone = requireAuthenticatedPhone();
        if (phone == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required", requestId));
        }

        return beneficiaryRepository.findByIdAndUserPhone(id, phone)
                .map(existing -> {
                    if (body.get("name") != null) existing.setName(body.get("name").trim());
                    if (body.get("phone") != null) existing.setPhone(body.get("phone"));
                    if (body.get("networkName") != null) existing.setNetworkName(body.get("networkName"));
                    if (body.get("networkColor") != null) existing.setNetworkColor(body.get("networkColor"));
                    if (body.get("billerCode") != null) existing.setBillerCode(body.get("billerCode"));
                    if (body.get("billerName") != null) existing.setBillerName(body.get("billerName"));
                    if (body.get("accountRef") != null) existing.setAccountRef(body.get("accountRef"));
                    Beneficiary saved = beneficiaryRepository.save(existing);
                    return ResponseEntity.ok(ApiResponse.success(saved, requestId));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("NOT_FOUND", "Beneficiary not found", requestId)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        String requestId = getRequestId(httpRequest);
        String phone = requireAuthenticatedPhone();
        if (phone == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Login required", requestId));
        }
        if (beneficiaryRepository.findByIdAndUserPhone(id, phone).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("NOT_FOUND", "Beneficiary not found", requestId));
        }
        beneficiaryRepository.deleteByIdAndUserPhone(id, phone);
        return ResponseEntity.ok(ApiResponse.success(null, requestId));
    }

    /** Same defensive pattern as TransactionController#myHistory. */
    private String requireAuthenticatedPhone() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return auth.getName();
    }

    private String getRequestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
