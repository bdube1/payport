package zw.co.payport.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.payport.gateway.entity.NetworkPrefix;
import zw.co.payport.gateway.entity.PaymentMethod;
import zw.co.payport.gateway.entity.ProductCurrency;
import zw.co.payport.gateway.entity.ProductType;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.repository.NetworkPrefixRepository;
import zw.co.payport.gateway.repository.PaymentMethodRepository;
import zw.co.payport.gateway.repository.ProductCurrencyRepository;
import zw.co.payport.gateway.repository.ProductTypeRepository;
import zw.co.payport.gateway.service.adaptor.netone.NetOneDataBundleService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Product Catalogue Controller
 *
 * GET /api/v1/products                         - All active products + their enabled currencies
 * GET /api/v1/products/{code}/currencies       - Enabled currencies for a specific product
 * GET /api/v1/products/detect-network/{phone}  - Phone prefix → network
 * GET /api/v1/products/payment-methods         - Active payment methods
 *
 * The response includes enabledCurrencies[] per product — the frontend
 * renders only what is enabled. When ops disables a currency, it disappears
 * from the consumer portal immediately (no cache, no deploy needed).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductCatalogueController {

    private final ProductTypeRepository      productTypeRepository;
    private final PaymentMethodRepository    paymentMethodRepository;
    private final NetworkPrefixRepository    networkPrefixRepository;
    private final ProductCurrencyRepository  productCurrencyRepository;
    private final NetOneDataBundleService    netOneDataBundleService;
    private final zw.co.payport.gateway.service.BundleService bundleService;

    /** Currencies switched off (or not live) in the portal are not offered to customers. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private zw.co.payport.gateway.markets.MarketRegistry marketRegistry;

    private boolean offered(ProductCurrency pc) {
        return marketRegistry == null || marketRegistry.liveCurrency(pc.getCurrency());
    }

    /**
     * Full product catalogue.
     * Each product includes an enabledCurrencies[] array — only the currencies
     * ops has switched on. Consumer portal never shows disabled currencies.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<?>> listProducts(HttpServletRequest req) {
        List<ProductType> products = productTypeRepository.findByIsActiveTrueOrderByCategory();

        // Bulk-load all active currencies once, group by product id
        Map<Long, List<ProductCurrency>> currenciesByProduct =
                productCurrencyRepository.findAllActiveForActiveProdcuts()
                        .stream()
                        .collect(Collectors.groupingBy(pc -> pc.getProductType().getId()));

        List<Map<String, Object>> response = products.stream().map(p -> {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id",            p.getId());
            dto.put("code",          p.getCode());
            dto.put("name",          p.getName());
            dto.put("category",      p.getCategory());
            dto.put("providerCode",  p.getProviderCode());
            dto.put("isActive",      p.getIsActive());
            dto.put("minAmountCents",p.getMinAmountCents());
            dto.put("maxAmountCents",p.getMaxAmountCents());
            dto.put("logoUrl",       p.getLogoUrl()); // Add logo URL to response

            // Enabled currencies for this product
            List<ProductCurrency> pcs = currenciesByProduct.getOrDefault(p.getId(), List.of()).stream().filter(this::offered).toList();
            dto.put("enabledCurrencies", pcs.stream().map(pc -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("currency",      pc.getCurrency());
                c.put("label",         pc.getLabel() != null ? pc.getLabel() : pc.getCurrency());
                c.put("minAmountCents",pc.effectiveMin(p));
                c.put("maxAmountCents",pc.effectiveMax(p));
                c.put("displayOrder",  pc.getDisplayOrder());
                return c;
            }).toList());

            // Convenience: list of just the currency codes e.g. ["USD","ZWG"]
            dto.put("supportedCurrencyCodes",
                    pcs.stream().map(ProductCurrency::getCurrency).toList());

            return dto;
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(response, getRequestId(req)));
    }

    /**
     * Enabled currencies for a single product.
     * Used by frontend when it needs to refresh just the currency options.
     */
    @GetMapping("/{code}/currencies")
    public ResponseEntity<ApiResponse<?>> getProductCurrencies(
            @PathVariable String code, HttpServletRequest req) {

        var productOpt = productTypeRepository.findByCodeAndIsActiveTrue(code.toUpperCase());
        if (productOpt.isEmpty()) {
            return ResponseEntity.ok(
                    ApiResponse.error("NOT_FOUND", "Product not found: " + code, getRequestId(req)));
        }

        ProductType p = productOpt.get();
        List<ProductCurrency> pcs = productCurrencyRepository.findActiveByProductId(p.getId()).stream().filter(this::offered).toList();

        List<Map<String, Object>> result = pcs.stream().map(pc -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("currency",      pc.getCurrency());
            c.put("label",         pc.getLabel() != null ? pc.getLabel() : pc.getCurrency());
            c.put("minAmountCents",pc.effectiveMin(p));
            c.put("maxAmountCents",pc.effectiveMax(p));
            return c;
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(result, getRequestId(req)));
    }

    @GetMapping("/payment-methods")
    public ResponseEntity<ApiResponse<?>> listPaymentMethods(HttpServletRequest req) {
        return ResponseEntity.ok(ApiResponse.success(
                paymentMethodRepository.findByIsActiveTrueOrderByDisplayOrder(),
                getRequestId(req)));
    }

    /**
     * Network detection from phone prefix.
     * e.g. +263771234567 → prefix "0771" → ECONET
     */
    @GetMapping("/detect-network/{phone}")
    public ResponseEntity<ApiResponse<Map<String, String>>> detectNetwork(
            @PathVariable String phone, HttpServletRequest req) {

        String normalised = phone.replaceAll("\\s+", "");
        if (normalised.startsWith("+263")) normalised = "0" + normalised.substring(4);
        if (normalised.startsWith("263"))  normalised = "0" + normalised.substring(3);

        if (normalised.length() < 4) {
            return ResponseEntity.ok(ApiResponse.error(
                    "INVALID_NUMBER", "Phone number too short", getRequestId(req)));
        }

        String prefix    = normalised.substring(0, 4);
        var    networkOpt = networkPrefixRepository.findByPrefix(prefix);

        if (networkOpt.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.error(
                    "UNKNOWN_NETWORK", "Network not recognised for prefix: " + prefix, getRequestId(req)));
        }

        NetworkPrefix np = networkOpt.get();
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("prefix", prefix, "networkCode", np.getNetworkCode(), "networkName", np.getNetworkName()),
                getRequestId(req)));
    }

    /**
     * Fetch live data bundles from the MNO for the given provider + currency.
     * Called by Data.jsx when the user selects a network and currency.
     *
     * GET /api/v1/products/data-bundles?provider=NETONE&currency=USD
     *
     * Returns list of: { bundleCode, name, dataSize, validity, amountCents, currency }
     */
    @GetMapping("/data-bundles")
    public ResponseEntity<ApiResponse<?>> getDataBundles(
            @RequestParam String provider,
            @RequestParam(defaultValue = "USD") String currency,
            HttpServletRequest req) {

        // The same bundles every channel shows: NetOne's live list, or the ones switched on in Platform setup → Bundles.
        String ccy = currency.toUpperCase();
        List<Map<String, Object>> bundles = bundleService.getActiveBundles(provider.toUpperCase(), ccy, null).stream().map(b -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("bundleCode", b.getBundleCode());
            m.put("name", b.getBundleName());
            m.put("dataSize", b.getDataVolume());
            m.put("validity", b.getValidity() != null ? b.getValidity() : b.getDescription());
            m.put("amountCents", Math.round((b.getPriceValue() == null ? 0 : b.getPriceValue()) * 100));
            m.put("currency", b.getCurrency());
            m.put("category", b.getCategory());
            return m;
        }).toList();

        return ResponseEntity.ok(ApiResponse.success(bundles, getRequestId(req)));
    }

    private String getRequestId(HttpServletRequest req) {
        String id = req.getHeader("X-Request-ID");
        return id != null ? id : java.util.UUID.randomUUID().toString();
    }
}