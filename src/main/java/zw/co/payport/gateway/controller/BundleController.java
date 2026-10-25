package zw.co.payport.gateway.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.model.request.BulkStatusUpdateRequest;
import zw.co.payport.gateway.model.request.CreateBundleRequest;
import zw.co.payport.gateway.model.request.UpdateBundleRequest;
import zw.co.payport.gateway.model.request.BundleUploadRequest;
import zw.co.payport.gateway.model.response.BundleResponse;
import zw.co.payport.gateway.service.BundleService;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/bundles")
@RequiredArgsConstructor
public class BundleController {

    private final BundleService bundleService;

    @GetMapping("/{provider}")
    public ResponseEntity<List<BundleResponse>> getBundles(
            @PathVariable String provider,
            @RequestParam(required = false, defaultValue = "USD") String currency,
            @RequestParam(required = false) String category) {

        log.info("[BUNDLE] Fetching bundles for provider: {} currency: {}", provider, currency);

        List<BundleResponse> bundles = bundleService.getActiveBundles(provider, currency, category);
        return ResponseEntity.ok(bundles);
    }

    @GetMapping("/{provider}/code/{bundleCode}")
    public ResponseEntity<BundleResponse> getBundleByCode(
            @PathVariable String provider,
            @PathVariable String bundleCode,
            @RequestParam(required = false, defaultValue = "USD") String currency) {

        log.info("[BUNDLE] Fetching bundle: {} for provider: {}", bundleCode, provider);

        BundleResponse bundle = bundleService.getBundleByCode(provider, bundleCode, currency);
        return ResponseEntity.ok(bundle);
    }

    @GetMapping("/{provider}/categories")
    public ResponseEntity<List<String>> getCategories(@PathVariable String provider) {
        return ResponseEntity.ok(bundleService.getCategories(provider));
    }

    /** Every bundle of a provider, on and off, for Platform setup → Bundles. */
    @GetMapping("/admin/{provider}/all")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<List<BundleResponse>> allBundles(@PathVariable String provider,
                                                           @RequestParam(required = false, defaultValue = "USD") String currency) {
        return ResponseEntity.ok(bundleService.getAllBundles(provider, currency));
    }

    /** Copies NetOne's live catalogue now (new bundles arrive switched off). */
    @PostMapping("/admin/NETONE/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> syncNetOne() {
        return ResponseEntity.ok(bundleService.syncNetOne());
    }

    @PostMapping("/admin/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> uploadBundles(@ModelAttribute BundleUploadRequest request) {

        log.info("[BUNDLE] Admin uploading {} bundles from file: {}",
                request.getProvider(), request.getFile().getOriginalFilename());

        Map<String, Object> result = bundleService.processExcelUpload(
                request.getFile(),
                request.getProvider(),
                request.getVersion()
        );

        return ResponseEntity.ok(result);
    }

    @PostMapping("/admin/{provider}/create")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BundleResponse> createBundle(
            @PathVariable String provider,
            @RequestBody @Valid CreateBundleRequest request) {

        log.info("[BUNDLE] Admin creating new bundle for {}: {}", provider, request.getBundleCode());

        BundleResponse created = bundleService.createBundle(provider, request);
        return ResponseEntity.status(201).body(created);
    }

    @PutMapping("/admin/{provider}/code/{bundleCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BundleResponse> updateBundle(
            @PathVariable String provider,
            @PathVariable String bundleCode,
            @RequestBody @Valid UpdateBundleRequest request) {

        log.info("[BUNDLE] Admin updating {} bundle: {}", provider, bundleCode);

        BundleResponse updated = bundleService.updateBundle(provider, bundleCode, request);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/admin/{provider}/code/{bundleCode}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> deactivateBundle(
            @PathVariable String provider,
            @PathVariable String bundleCode) {

        bundleService.deactivateBundle(provider, bundleCode);

        return ResponseEntity.ok(Map.of(
                "message", "Bundle deactivated successfully",
                "provider", provider,
                "bundleCode", bundleCode
        ));
    }

    @PostMapping("/admin/{provider}/bulk-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> bulkUpdateStatus(
            @PathVariable String provider,
            @RequestBody BulkStatusUpdateRequest request) {

        int updated = bundleService.bulkUpdateStatus(provider, request);

        return ResponseEntity.ok(Map.of(
                "message", "Bulk status update completed",
                "updatedCount", updated
        ));
    }
}