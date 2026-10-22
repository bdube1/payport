package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.entity.*;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.*;
import zw.co.payport.gateway.service.AuthService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ops Product & Currency Configuration Controller
 *
 * All routes require ADMIN or OPS_MANAGER role.
 *
 * Product management:
 *   GET    /api/v1/admin/config/products                      - All products (active + inactive)
 *   POST   /api/v1/admin/config/products                      - Create product
 *   PUT    /api/v1/admin/config/products/{id}                 - Update product (name, active, limits, logo)
 *   DELETE /api/v1/admin/config/products/{id}                 - Deactivate product
 *
 * Currency management per product:
 *   GET    /api/v1/admin/config/products/{id}/currencies      - All currencies for product
 *   PUT    /api/v1/admin/config/products/{id}/currencies/{ccy} - Enable/disable/update currency
 *
 * Network prefix management:
 *   GET    /api/v1/admin/config/prefixes                      - All network prefixes
 *   POST   /api/v1/admin/config/prefixes                      - Create new prefix
 *   PUT    /api/v1/admin/config/prefixes/{id}                 - Update prefix
 *   DELETE /api/v1/admin/config/prefixes/{id}                 - Delete prefix
 *
 * Payment method management:
 *   GET    /api/v1/admin/config/payment-methods               - All payment methods
 *   PUT    /api/v1/admin/config/payment-methods/{id}          - Toggle active, update order
 *
 * User management:
 *   GET    /api/v1/admin/users                                 - List all users
 *   POST   /api/v1/admin/users                                 - Create new user
 *   PUT    /api/v1/admin/users/{id}                            - Update user
 *   DELETE /api/v1/admin/users/{id}                            - Deactivate user
 *
 * Logs:
 *   GET    /api/v1/admin/logs/audit                            - Transaction audit logs
 *   GET    /api/v1/admin/logs/notifications                    - Notification logs
 *
 * File upload:
 *   POST   /api/v1/admin/upload-logo                           - Upload product logo image
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPS_MANAGER')")
public class OpsProductConfigController {

    private final ProductTypeRepository     productTypeRepository;
    private final ProductCurrencyRepository productCurrencyRepository;
    private final PaymentMethodRepository   paymentMethodRepository;
    private final NetworkPrefixRepository   networkPrefixRepository;
    private final UserRepository            userRepository;
    private final TransactionAuditLogRepository auditLogRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final AuthService               authService;
    private final zw.co.payport.gateway.service.SmsNotificationService smsNotificationService;

    /** Product, currency and payment-method switches go to the hash-chained audit log. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.risk.ComplianceAudit configAudit;

    private void auditConfig(HttpServletRequest req, String action, String subject, String details) {
        if (configAudit != null) configAudit.record("staff:" + principal(req), action, subject, details);
    }

    @Value("${payport.upload.logo-directory:./uploads/logos}")
    private String logoUploadDirectory;

    @Value("${payport.upload.base-url:https://api.payport.africa}")
    private String baseUrl;

    // ─────────────────────────────────────────────────────────────────────────
    // PRODUCTS
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/config/products")
    public ResponseEntity<ApiResponse<?>> getAllProducts(HttpServletRequest req) {
        List<ProductType> all = productTypeRepository.findAll(
                org.springframework.data.domain.Sort.by("category", "code"));

        // Build response including all currency rows (active + inactive) per product
        Map<Long, List<ProductCurrency>> currencyMap =
                productCurrencyRepository.findAll().stream()
                        .collect(Collectors.groupingBy(pc -> pc.getProductType().getId()));

        List<Map<String, Object>> result = all.stream().map(p -> {
            Map<String, Object> dto = buildProductDto(p,
                    currencyMap.getOrDefault(p.getId(), List.of()), false);
            return dto;
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(result, rid(req)));
    }

    @PostMapping("/config/products")
    public ResponseEntity<ApiResponse<?>> createProduct(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        ProductType p = ProductType.builder()
                .code(str(body, "code").toUpperCase())
                .name(str(body, "name"))
                .category(str(body, "category").toUpperCase())
                .providerCode(str(body, "providerCode").toUpperCase())
                .isActive(bool(body, "isActive", false))
                .minAmountCents(lng(body, "minAmountCents", 50L))
                .maxAmountCents(lng(body, "maxAmountCents", 500000L))
                .currency("USD")
                .logoUrl(str(body, "logoUrl"))
                .fulfilmentConfig(str(body, "fulfilmentConfig"))
                .build();
        p = productTypeRepository.save(p);
        log.info("[CONFIG] Product created: {} by {}", p.getCode(), principal(req));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(buildProductDto(p, List.of(), false), rid(req)));
    }

    @PutMapping("/config/products/{id}")
    public ResponseEntity<ApiResponse<?>> updateProduct(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {

        ProductType p = productTypeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));

        if (body.containsKey("name"))               p.setName(str(body, "name"));
        if (body.containsKey("isActive"))           p.setIsActive(bool(body, "isActive", p.getIsActive()));
        if (body.containsKey("minAmountCents"))     p.setMinAmountCents(lng(body, "minAmountCents", p.getMinAmountCents()));
        if (body.containsKey("maxAmountCents"))     p.setMaxAmountCents(lng(body, "maxAmountCents", p.getMaxAmountCents()));
        if (body.containsKey("logoUrl"))            p.setLogoUrl(str(body, "logoUrl"));
        if (body.containsKey("fulfilmentConfig"))   p.setFulfilmentConfig(str(body, "fulfilmentConfig"));

        p = productTypeRepository.save(p);
        log.info("[CONFIG] Product {} updated by {}: active={}", p.getCode(), principal(req), p.getIsActive());
        auditConfig(req, "PRODUCT_UPDATED", "product:" + p.getCode(), "active=" + p.getIsActive() + " changes=" + body.keySet());

        List<ProductCurrency> currencies = productCurrencyRepository.findAllByProductId(p.getId());
        return ResponseEntity.ok(ApiResponse.success(buildProductDto(p, currencies, false), rid(req)));
    }

    @DeleteMapping("/config/products/{id}")
    public ResponseEntity<ApiResponse<String>> deactivateProduct(
            @PathVariable Long id, HttpServletRequest req) {
        ProductType p = productTypeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        p.setIsActive(false);
        productTypeRepository.save(p);
        log.info("[CONFIG] Product {} deactivated by {}", p.getCode(), principal(req));
        return ResponseEntity.ok(ApiResponse.success("Product deactivated", rid(req)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CURRENCY MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get all currencies for a product (includes inactive ones for ops view).
     */
    @GetMapping("/config/products/{id}/currencies")
    public ResponseEntity<ApiResponse<?>> getProductCurrencies(
            @PathVariable Long id, HttpServletRequest req) {

        ProductType p = productTypeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        List<ProductCurrency> currencies = productCurrencyRepository.findAllByProductId(id);
        return ResponseEntity.ok(ApiResponse.success(
                buildCurrencyDtoList(p, currencies), rid(req)));
    }

    /**
     * Enable, disable, or update limits for a currency on a product.
     *
     * PUT body:
     * {
     *   "isActive": true,
     *   "minAmountCents": 100,    // optional override
     *   "maxAmountCents": 500000, // optional override
     *   "label": "USD $"          // optional display label
     * }
     */
    @PutMapping("/config/products/{id}/currencies/{currency}")
    public ResponseEntity<ApiResponse<?>> updateProductCurrency(
            @PathVariable Long id,
            @PathVariable String currency,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {

        String ccy = currency.toUpperCase();
        if (!ccy.equals("USD") && !ccy.equals("ZWG")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_CURRENCY",
                            "Only USD and ZWG are supported", rid(req)));
        }

        ProductType p = productTypeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));

        // Find or create the currency row
        Optional<ProductCurrency> existing = productCurrencyRepository
                .findByProductCodeAndCurrency(p.getCode(), ccy);

        ProductCurrency pc;
        if (existing.isPresent()) {
            pc = existing.get();
        } else {
            pc = ProductCurrency.builder()
                    .productType(p)
                    .currency(ccy)
                    .isActive(false)
                    .displayOrder(ccy.equals("USD") ? 0 : 1)
                    .build();
        }

        if (body.containsKey("isActive"))           pc.setIsActive(bool(body, "isActive", pc.getIsActive()));
        if (body.containsKey("minAmountCents"))     pc.setMinAmountCents(lngNullable(body, "minAmountCents"));
        if (body.containsKey("maxAmountCents"))     pc.setMaxAmountCents(lngNullable(body, "maxAmountCents"));
        if (body.containsKey("label"))              pc.setLabel(str(body, "label"));
        if (body.containsKey("displayOrder"))       pc.setDisplayOrder((int) lng(body, "displayOrder", pc.getDisplayOrder()));
        pc.setUpdatedBy(principal(req));
        pc.setUpdatedAt(LocalDateTime.now());

        pc = productCurrencyRepository.save(pc);

        log.info("[CONFIG] Currency {} for product {} set active={} by {}",
                ccy, p.getCode(), pc.getIsActive(), principal(req));
        auditConfig(req, "PRODUCT_CURRENCY_UPDATED", "product:" + p.getCode() + ":" + ccy, "active=" + pc.getIsActive());

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id",            pc.getId());
        dto.put("productId",     id);
        dto.put("productCode",   p.getCode());
        dto.put("currency",      pc.getCurrency());
        dto.put("label",         pc.getLabel());
        dto.put("isActive",      pc.getIsActive());
        dto.put("minAmountCents",pc.effectiveMin(p));
        dto.put("maxAmountCents",pc.effectiveMax(p));
        dto.put("displayOrder",  pc.getDisplayOrder());
        dto.put("updatedBy",     pc.getUpdatedBy());
        dto.put("updatedAt",     pc.getUpdatedAt());

        return ResponseEntity.ok(ApiResponse.success(dto, rid(req)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NETWORK PREFIXES
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/config/prefixes")
    public ResponseEntity<ApiResponse<List<NetworkPrefix>>> getPrefixes(HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                networkPrefixRepository.findAll(Sort.by("networkCode", "prefix")), rid(req)));
    }

    @PostMapping("/config/prefixes")
    public ResponseEntity<ApiResponse<NetworkPrefix>> createPrefix(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        NetworkPrefix p = new NetworkPrefix();
        p.setPrefix(str(body, "prefix"));
        p.setNetworkCode(str(body, "networkCode").toUpperCase());
        p.setNetworkName(str(body, "networkName"));
        p.setCountryCode(body.getOrDefault("countryCode", "ZW").toString());
        p = networkPrefixRepository.save(p);
        log.info("[CONFIG] Prefix created: {} → {} by {}", p.getPrefix(), p.getNetworkCode(), principal(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(p, rid(req)));
    }

    @PutMapping("/config/prefixes/{id}")
    public ResponseEntity<ApiResponse<NetworkPrefix>> updatePrefix(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        NetworkPrefix p = networkPrefixRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Prefix not found: " + id));
        if (body.containsKey("networkCode")) p.setNetworkCode(str(body, "networkCode").toUpperCase());
        if (body.containsKey("networkName")) p.setNetworkName(str(body, "networkName"));
        if (body.containsKey("prefix")) p.setPrefix(str(body, "prefix"));
        p = networkPrefixRepository.save(p);
        log.info("[CONFIG] Prefix {} updated by {}", id, principal(req));
        return ResponseEntity.ok(ApiResponse.success(p, rid(req)));
    }

    @DeleteMapping("/config/prefixes/{id}")
    public ResponseEntity<ApiResponse<String>> deletePrefix(
            @PathVariable Long id, HttpServletRequest req) {
        networkPrefixRepository.deleteById(id);
        log.info("[CONFIG] Prefix {} deleted by {}", id, principal(req));
        return ResponseEntity.ok(ApiResponse.success("Prefix deleted", rid(req)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PAYMENT METHODS
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/config/payment-methods")
    public ResponseEntity<ApiResponse<?>> getAllPaymentMethods(HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                paymentMethodRepository.findAll(
                        org.springframework.data.domain.Sort.by("displayOrder")),
                rid(req)));
    }

    @PutMapping("/config/payment-methods/{id}")
    public ResponseEntity<ApiResponse<?>> updatePaymentMethod(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {

        var pm = paymentMethodRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payment method not found: " + id));

        if (body.containsKey("isActive"))     pm.setIsActive(bool(body, "isActive", pm.getIsActive()));
        if (body.containsKey("displayOrder")) pm.setDisplayOrder((int) lng(body, "displayOrder", pm.getDisplayOrder()));
        if (body.containsKey("name"))         pm.setName(str(body, "name"));

        pm = paymentMethodRepository.save(pm);
        log.info("[CONFIG] PaymentMethod {} set active={} by {}", pm.getCode(), pm.getIsActive(), principal(req));
        auditConfig(req, "PAYMENT_METHOD_UPDATED", "payment_method:" + pm.getCode(), "active=" + pm.getIsActive());
        return ResponseEntity.ok(ApiResponse.success(pm, rid(req)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // USER MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUsers(HttpServletRequest req) {
        List<Map<String, Object>> users = userRepository.findAll(Sort.by("registeredAt").descending())
                .stream().map(this::safeUser).toList();
        return ResponseEntity.ok(ApiResponse.success(users, rid(req)));
    }

    @PostMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createUser(
            @RequestBody Map<String, Object> body, HttpServletRequest req) {
        String phone = authService.normalizePhone(str(body, "phone"));
        if (userRepository.existsByPhone(phone)) {
            throw new IllegalArgumentException("User with phone " + phone + " already exists");
        }

        String role = body.getOrDefault("role", "CUSTOMER").toString();
        boolean isStaff = !"CUSTOMER".equals(role);

        String email = str(body, "email").trim().toLowerCase();
        if (isStaff) {
            // Staff accounts require an email -- item 11 -- both as their
            // login identifier and as the password-reset destination.
            if (email.isBlank()) {
                throw new IllegalArgumentException("Email is required for OPS/ADMIN/FINANCE accounts");
            }
            if (userRepository.existsByEmail(email)) {
                throw new IllegalArgumentException("User with email " + email + " already exists");
            }
            authService.validatePasswordPolicy(str(body, "pin"));
        }

        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        User.UserBuilder builder = User.builder()
                .fullName(str(body, "fullName").trim())
                .phone(phone)
                .pinHash(enc.encode(str(body, "pin")))
                .role(role)
                .isActive(true)
                .isVerified(true)
                .failedPinAttempts(0);
        if (isStaff) {
            builder.email(email).passwordSetAt(java.time.LocalDateTime.now());
        }
        User user = builder.build();
        user = userRepository.save(user);
        log.info("[CONFIG] User created: {} role: {} by {}", user.getPhone(), user.getRole(), principal(req));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(safeUser(user), rid(req)));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateUser(
            @PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        if (body.containsKey("fullName")) user.setFullName(str(body, "fullName"));
        if (body.containsKey("role")) user.setRole(str(body, "role"));
        if (body.containsKey("isActive")) user.setIsActive((Boolean) body.get("isActive"));
        if (body.containsKey("email")) {
            String newEmail = str(body, "email").trim().toLowerCase();
            if (!newEmail.isBlank() && !newEmail.equals(user.getEmail()) && userRepository.existsByEmail(newEmail)) {
                throw new IllegalArgumentException("User with email " + newEmail + " already exists");
            }
            user.setEmail(newEmail.isBlank() ? null : newEmail);
        }
        if (body.containsKey("pin") && !str(body, "pin").isBlank()) {
            boolean isStaff = !"CUSTOMER".equals(user.getRole());
            if (isStaff) {
                authService.validatePasswordPolicy(str(body, "pin"));
            }
            org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
            user.setPinHash(enc.encode(str(body, "pin")));
            user.setPasswordSetAt(java.time.LocalDateTime.now());
            user.setMustChangePassword(false);
        }
        user = userRepository.save(user);
        log.info("[CONFIG] User {} updated by {}", id, principal(req));
        return ResponseEntity.ok(ApiResponse.success(safeUser(user), rid(req)));
    }

    /** Admin-only unlock (item 12/13). */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users/{id}/unlock")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unlockUser(
            @PathVariable Long id, HttpServletRequest req) {
        authService.unlockAccount(id);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        log.info("[CONFIG] User {} unlocked by {}", id, principal(req));
        return ResponseEntity.ok(ApiResponse.success(safeUser(user), rid(req)));
    }

    /**
     * A customer forgot their PIN entirely (different from being locked
     * out after failed attempts -- unlockUser above handles that case).
     * Generates a fresh random 4-digit PIN, shown to ops exactly once so
     * they can communicate it to the customer directly -- same "shown
     * once, never retrievable again" principle used for API keys and
     * merchant team temporary passwords.
     */
    @PostMapping("/users/{id}/reset-pin")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resetCustomerPin(
            @PathVariable Long id, HttpServletRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        if (!"CUSTOMER".equals(user.getRole())) {
            return ResponseEntity.badRequest().body(ApiResponse.error(
                    "NOT_A_CUSTOMER", "PIN reset only applies to CUSTOMER accounts -- staff use password reset instead", rid(req)));
        }

        String newPin = String.format("%04d", new java.security.SecureRandom().nextInt(10000));
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        user.setPinHash(enc.encode(newPin));
        user.setFailedPinAttempts(0);
        user.setCredentialsChangedAt(java.time.LocalDateTime.now());
        user.setMustChangePassword(true);
        userRepository.save(user);
        smsNotificationService.sendPinReset(user.getPhone(), newPin);

        log.info("[CONFIG] PIN reset for customer {} by {}", id, principal(req));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", id);
        body.put("newPin", newPin);
        body.put("warning", "This PIN has been texted to the customer's registered number, and they'll be required to set a new one on their next login. It's also shown here once, in case the SMS doesn't arrive.");
        return ResponseEntity.ok(ApiResponse.success(body, rid(req)));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<ApiResponse<String>> deactivateUser(
            @PathVariable Long id, HttpServletRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        user.setIsActive(false);
        userRepository.save(user);
        log.info("[CONFIG] User {} deactivated by {}", id, principal(req));
        return ResponseEntity.ok(ApiResponse.success("User deactivated", rid(req)));
    }

    @PostMapping("/users/{id}/activate")
    public ResponseEntity<ApiResponse<String>> activateUser(
            @PathVariable Long id, HttpServletRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
        user.setIsActive(true);
        user.setFailedPinAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        log.info("[CONFIG] User {} activated by {}", id, principal(req));
        return ResponseEntity.ok(ApiResponse.success("User activated", rid(req)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOGS
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/logs/audit")
    public ResponseEntity<ApiResponse<List<TransactionAuditLog>>> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String reference,
            HttpServletRequest req) {

        List<TransactionAuditLog> logs;
        if (reference != null && !reference.isBlank()) {
            logs = auditLogRepository.findByTransactionReferenceOrderByCreatedAtAsc(reference);
        } else {
            Page<TransactionAuditLog> p = auditLogRepository.findAll(
                    PageRequest.of(page, size, Sort.by("createdAt").descending()));
            logs = p.getContent();
        }
        return ResponseEntity.ok(ApiResponse.success(logs, rid(req)));
    }

    @GetMapping("/logs/notifications")
    public ResponseEntity<ApiResponse<List<NotificationLog>>> getNotificationLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest req) {
        Page<NotificationLog> p = notificationLogRepository.findAll(
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.success(p.getContent(), rid(req)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FILE UPLOAD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upload a logo image for a product
     * POST /api/v1/admin/upload-logo
     *
     * @param file the uploaded image file
     * @param req HTTP request
     * @return response with the URL of the uploaded logo
     */
    @PostMapping("/upload-logo")
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadLogo(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest req) {

        String requestId = rid(req);
        log.info("[UPLOAD] Received logo upload request: {}", file.getOriginalFilename());

        try {
            // Validate file
            if (file.isEmpty()) {
                log.warn("[UPLOAD] Upload failed: File is empty");
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("FILE_EMPTY", "Uploaded file is empty", requestId));
            }

            // Validate file type
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                log.warn("[UPLOAD] Upload failed: Invalid file type - {}", contentType);
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("INVALID_FILE_TYPE",
                                "Only image files are allowed (PNG, JPG, SVG)", requestId));
            }

            // Validate file size (max 2MB)
            long maxSize = 2 * 1024 * 1024; // 2MB
            if (file.getSize() > maxSize) {
                log.warn("[UPLOAD] Upload failed: File too large - {} bytes", file.getSize());
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("FILE_TOO_LARGE",
                                "File size must be less than 2MB", requestId));
            }

            // Generate unique filename
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            } else {
                // Default extension based on content type
                if (contentType.contains("png")) extension = ".png";
                else if (contentType.contains("jpeg") || contentType.contains("jpg")) extension = ".jpg";
                else if (contentType.contains("svg")) extension = ".svg";
                else extension = ".png";
            }

            String filename = UUID.randomUUID().toString() + extension;

            // Create upload directory if it doesn't exist
            Path uploadPath = Paths.get(logoUploadDirectory);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
                log.info("[UPLOAD] Created upload directory: {}", uploadPath);
            }

            // Save file
            Path filePath = uploadPath.resolve(filename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("[UPLOAD] File saved successfully: {}", filePath);

            // Generate URL for the uploaded file
            String logoUrl = "/uploads/logos/" + filename;

            // If you want the full URL including base URL:
            // String fullLogoUrl = baseUrl + "/uploads/logos/" + filename;

            Map<String, String> response = new HashMap<>();
            response.put("logoUrl", logoUrl);
            // response.put("fullLogoUrl", fullLogoUrl); // Uncomment if you want full URL

            log.info("[UPLOAD] Upload successful: {}", logoUrl);
            return ResponseEntity.ok(ApiResponse.success(response, requestId));

        } catch (IOException e) {
            log.error("[UPLOAD] File upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("UPLOAD_FAILED",
                            "Failed to upload file: " + e.getMessage(), requestId));
        } catch (Exception e) {
            log.error("[UPLOAD] Unexpected error during upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("UPLOAD_FAILED",
                            "An unexpected error occurred: " + e.getMessage(), requestId));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> buildProductDto(ProductType p,
                                                List<ProductCurrency> currencies,
                                                boolean activeOnly) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id",            p.getId());
        dto.put("code",          p.getCode());
        dto.put("name",          p.getName());
        dto.put("category",      p.getCategory());
        dto.put("providerCode",  p.getProviderCode());
        dto.put("isActive",      p.getIsActive());
        dto.put("minAmountCents",p.getMinAmountCents());
        dto.put("maxAmountCents",p.getMaxAmountCents());
        dto.put("logoUrl",       p.getLogoUrl());
        dto.put("fulfilmentConfig", p.getFulfilmentConfig());
        dto.put("currencies",    buildCurrencyDtoList(p, activeOnly
                ? currencies.stream().filter(ProductCurrency::getIsActive).toList()
                : currencies));
        return dto;
    }

    private List<Map<String, Object>> buildCurrencyDtoList(ProductType p,
                                                           List<ProductCurrency> currencies) {
        return currencies.stream().map(pc -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id",            pc.getId());
            c.put("currency",      pc.getCurrency());
            c.put("label",         pc.getLabel() != null ? pc.getLabel() : pc.getCurrency());
            c.put("isActive",      pc.getIsActive());
            c.put("minAmountCents",pc.effectiveMin(p));
            c.put("maxAmountCents",pc.effectiveMax(p));
            c.put("displayOrder",  pc.getDisplayOrder());
            c.put("updatedAt",     pc.getUpdatedAt());
            c.put("updatedBy",     pc.getUpdatedBy());
            return c;
        }).toList();
    }

    private Map<String, Object> safeUser(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("fullName", u.getFullName());
        m.put("phone", u.getPhone());
        m.put("email", u.getEmail());
        m.put("role", u.getRole());
        m.put("isActive", u.getIsActive());
        m.put("isVerified", u.getIsVerified());
        m.put("accountLocked", Boolean.TRUE.equals(u.getAccountLocked()));
        m.put("mustChangePassword", Boolean.TRUE.equals(u.getMustChangePassword()));
        m.put("lastLoginAt", u.getLastLoginAt());
        m.put("registeredAt", u.getRegisteredAt());
        return m;
    }

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k); return v != null ? v.toString() : "";
    }

    private boolean bool(Map<String, Object> m, String k, Boolean def) {
        Object v = m.get(k);
        if (v == null) return def != null && def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    private long lng(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return def; }
    }

    private Long lngNullable(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private String rid(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : UUID.randomUUID().toString();
    }

    private String principal(HttpServletRequest req) {
        Object p = req.getAttribute("authenticatedUsername");
        return p != null ? p.toString() : "ops";
    }
}