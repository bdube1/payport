package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import zw.co.payport.gateway.entity.BundleProduct;
import zw.co.payport.gateway.exception.ResourceNotFoundException;
import zw.co.payport.gateway.model.request.CreateBundleRequest;
import zw.co.payport.gateway.model.request.UpdateBundleRequest;
import zw.co.payport.gateway.model.request.BulkStatusUpdateRequest;
import zw.co.payport.gateway.model.response.BundleResponse;
import zw.co.payport.gateway.repository.BundleProductRepository;
import zw.co.payport.gateway.service.adaptor.netone.NetOneAirtimeAdaptor;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BundleService {

    private final BundleProductRepository bundleRepository;
    private final NetOneAirtimeAdaptor netOneAdaptor;
    private final zw.co.payport.gateway.service.adaptor.netone.NetOneDataBundleService netOneData;

    /** When NetOne's catalogue was last copied into the bundle table. */
    private volatile long netOneSyncedAt = 0;
    private static final long NETONE_SYNC_EVERY_MS = 30 * 60 * 1000L;

    @Cacheable(value = "bundles", key = "#provider + '-' + #currency + '-' + #category", condition = "!'NETONE'.equalsIgnoreCase(#provider)")
    public List<BundleResponse> getActiveBundles(String provider, String currency, String category) {
        // NetOne (owner, 2026-10-25): customers on every channel see exactly what NetOne returns, asked live.
        if ("NETONE".equalsIgnoreCase(provider)) {
            return netOneLive(currency);
        }
        
        // For ECONET, TELECEL - from database
        List<BundleProduct> bundles;
        if (category != null && !category.isEmpty()) {
            bundles = bundleRepository.findByProviderCodeAndCategoryAndIsActiveTrueOrderByDisplayOrder(
                    provider.toUpperCase(), category);
        } else {
            bundles = bundleRepository.findByProviderCodeAndIsActiveTrueOrderByDisplayOrder(
                    provider.toUpperCase());
        }

        return bundles.stream()
                .map(b -> BundleResponse.from(b, currency))
                .toList();
    }

    private final Map<String, Object[]> netOneLiveCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long NETONE_LIVE_TTL_MS = 2 * 60 * 1000L;

    /** NetOne's live list, kept for two minutes when it has bundles in it (an empty answer is asked again next time). */
    public List<BundleResponse> netOneLive(String currency) {
        String ccy = currency == null || currency.isBlank() ? "USD" : currency.toUpperCase();
        if ("ZWL".equals(ccy)) ccy = "ZWG";
        Object[] hit = netOneLiveCache.get(ccy);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < NETONE_LIVE_TTL_MS) {
            @SuppressWarnings("unchecked") List<BundleResponse> cached = (List<BundleResponse>) hit[1];
            return cached;
        }
        List<BundleResponse> live = getNetOneBundlesFromApi(ccy);
        final String c = ccy;
        live.forEach(b -> {
            b.setIsActive(true);
            b.setProviderCode("NETONE");
            if (b.getCurrency() == null) b.setCurrency(c);
            if (b.getPrice() == null && b.getPriceValue() != null)
                b.setPrice("USD".equals(c) ? String.format("$%.2f", b.getPriceValue()) : String.format("ZiG %.2f", b.getPriceValue()));
        });
        if (!live.isEmpty()) netOneLiveCache.put(ccy, new Object[] { System.currentTimeMillis(), live });
        return live;
    }

    /**
     * Fetch NetOne bundles directly from their external API
     */
    private List<BundleResponse> getNetOneBundlesFromApi(String currency) {
        try {
            log.info("[NETONE] Fetching bundles from external API for currency: {}", currency);
            
            // Use a unique agent reference for this request
            String agentReference = "BUNDLE-FETCH-" + System.currentTimeMillis();
            
            // Call the NetOne adaptor to get available bundles
            NetOneAirtimeAdaptor.AvailableBundlesResult result = 
                netOneAdaptor.getAvailableDataBundles(agentReference, currency);
            
            if (!result.isSuccess()) {
                log.warn("[NETONE] Failed to fetch bundles: ReplyCode={}, Message={}", 
                        result.getReplyCode(), result.getReplyMsg());
                return Collections.emptyList();
            }
            
            // Parse the bundles from the response
            Object bundlesObj = result.getBundles();
            List<BundleResponse> bundleResponses = new ArrayList<>();
            
            if (bundlesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> bundles = (List<Map<String, Object>>) bundlesObj;
                
                // Log the first bundle to understand its structure
                if (!bundles.isEmpty()) {
                    log.info("[NETONE] First bundle raw data structure: {}", bundles.get(0));
                    log.info("[NETONE] Bundle keys: {}", bundles.get(0).keySet());
                }
                
                for (Map<String, Object> bundleData : bundles) {
                    BundleResponse response = convertNetOneBundleToResponse(bundleData, currency);
                    if (response != null && (response.getPriceValue() > 0 || !response.getBundleName().isEmpty())) {
                        bundleResponses.add(response);
                    }
                }
            }
            
            log.info("[NETONE] Successfully fetched {} bundles from API", bundleResponses.size());
            return bundleResponses;
            
        } catch (Exception e) {
            log.error("[NETONE] Error fetching bundles from external API: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Convert NetOne API bundle data to BundleResponse format
     * NetOne API returns bundles with various field name formats
     */
    private BundleResponse convertNetOneBundleToResponse(Map<String, Object> bundleData, String currency) {
        try {
            BundleResponse response = new BundleResponse();
            
            // Try all possible field name variations
            String bundleCode = getStringValue(bundleData, 
                "bundleCode", "BundleCode", "code", "Code", "productCode", "ProductCode", 
                "bundleId", "BundleId", "ID", "id", "offerCode", "OfferCode");
            
            String bundleName = getStringValue(bundleData, 
                "bundleName", "BundleName", "name", "Name", "productName", "ProductName", 
                "description", "Description", "offerName", "OfferName", "title", "Title");
            
            String category = getStringValue(bundleData, 
                "category", "Category", "type", "Type", "bundleCategory", "BundleCategory", 
                "productType", "ProductType", "offerType", "OfferType");
            
            // Extract price - handle various field names and nested structures
            Double price = null;
            
            // Check for price in various field names
            price = getDoubleValue(bundleData, 
                "price", "Price", "amount", "Amount", "cost", "Cost", 
                "priceValue", "PriceValue", "bundlePrice", "BundlePrice",
                "denomination", "Denomination", "value", "Value");
            
            // If price is still null, try to find any numeric field that might be the price
            if (price == null) {
                for (Map.Entry<String, Object> entry : bundleData.entrySet()) {
                    if (entry.getKey().toLowerCase().contains("price") || 
                        entry.getKey().toLowerCase().contains("amount") ||
                        entry.getKey().toLowerCase().contains("cost")) {
                        price = getDoubleValue(bundleData, entry.getKey());
                        if (price != null && price > 0) break;
                    }
                }
            }
            
            // Extract data volume
            String dataVolume = getStringValue(bundleData, 
                "dataVolume", "DataVolume", "volume", "Volume", "data", "Data", 
                "mb", "MB", "dataAmount", "DataAmount", "quota", "Quota");
            
            // If dataVolume is a number, format it nicely
            if (dataVolume != null && !dataVolume.isEmpty()) {
                try {
                    double vol = Double.parseDouble(dataVolume);
                    if (vol >= 1024) {
                        dataVolume = String.format("%.1f GB", vol / 1024);
                    } else {
                        dataVolume = String.format("%.0f MB", vol);
                    }
                } catch (NumberFormatException e) {
                    // Keep as is - might already be formatted
                }
            }
            
            // Extract validity
            String validity = getStringValue(bundleData, 
                "validity", "Validity", "duration", "Duration", "validDays", "ValidDays",
                "validityPeriod", "ValidityPeriod", "period", "Period", "expiry", "Expiry");
            
            // Set the response values
            response.setBundleCode(bundleCode != null && !bundleCode.isEmpty() ? bundleCode : UUID.randomUUID().toString().substring(0, 8));
            response.setBundleName(bundleName != null && !bundleName.isEmpty() ? bundleName : "NetOne Data Bundle");
            response.setCategory(category != null && !category.isEmpty() ? category : "Data Bundles");
            response.setPriceValue(price != null && price > 0 ? price : 0.0);
            response.setDataVolume(dataVolume != null && !dataVolume.isEmpty() ? dataVolume : "");
            response.setValidity(validity != null && !validity.isEmpty() ? validity : "");
            
            // Log the parsed bundle for debugging
            if (response.getPriceValue() > 0) {
                log.debug("[NETONE] Parsed bundle: Code={}, Name={}, Price={}, Volume={}, Validity={}", 
                    response.getBundleCode(), response.getBundleName(), response.getPriceValue(), 
                    response.getDataVolume(), response.getValidity());
            }
            
            return response;
        } catch (Exception e) {
            log.warn("[NETONE] Failed to convert bundle data: {}", e.getMessage());
            return null;
        }
    }
    
    private String getStringValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                if (value instanceof String) {
                    String str = ((String) value).trim();
                    if (!str.isEmpty()) return str;
                } else if (value instanceof Number) {
                    return value.toString();
                }
            }
        }
        return "";
    }
    
    private Double getDoubleValue(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                if (value instanceof Number) {
                    return ((Number) value).doubleValue();
                } else if (value instanceof String) {
                    try {
                        String str = ((String) value).trim();
                        if (!str.isEmpty()) {
                            // Handle currency symbols like $, USD, etc.
                            String cleanStr = str.replaceAll("[^0-9.]", "");
                            if (!cleanStr.isEmpty()) {
                                return Double.parseDouble(cleanStr);
                            }
                        }
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            }
        }
        return null;
    }

    public BundleResponse getBundleByCode(String provider, String bundleCode, String currency) {
        BundleProduct bundle = bundleRepository
                .findByProviderCodeAndBundleCode(provider.toUpperCase(), bundleCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bundle not found: " + provider + " - " + bundleCode));

        return BundleResponse.from(bundle, currency);
    }

    public List<String> getCategories(String provider) {
        return bundleRepository.findDistinctCategoriesByProviderCode(provider.toUpperCase());
    }

    @Transactional
    @CacheEvict(value = "bundles", allEntries = true)
    public BundleResponse createBundle(String provider, CreateBundleRequest request) {
        Optional<BundleProduct> existing = bundleRepository
                .findByProviderCodeAndBundleCode(provider.toUpperCase(), request.getBundleCode());

        if (existing.isPresent()) {
            throw new IllegalArgumentException(
                    "Bundle with code " + request.getBundleCode() + " already exists for " + provider
            );
        }

        BundleProduct bundle = new BundleProduct();
        bundle.setProviderCode(provider.toUpperCase());
        bundle.setProviderName(getProviderName(provider));
        bundle.setBundleCode(request.getBundleCode());
        bundle.setBundleName(request.getBundleName());
        bundle.setCategory(request.getCategory());
        bundle.setPriceUsdCents(request.getPriceUsdCents());
        bundle.setPriceZwgCents(request.getPriceZwgCents());
        bundle.setDataVolumeMb(request.getDataVolumeMb());
        bundle.setValidityDays(request.getValidityDays());
        bundle.setValidityHours(request.getValidityHours());
        bundle.setIncludesSms(request.getIncludesSms());
        bundle.setIncludesVoiceMinutes(request.getIncludesVoiceMinutes());
        bundle.setDescription(request.getDescription());
        bundle.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        bundle.setDisplayOrder(0);
        bundle.setCreatedAt(LocalDateTime.now());

        BundleProduct saved = bundleRepository.save(bundle);
        log.info("[BUNDLE] Created new bundle: {} - {} for provider: {}",
                saved.getBundleCode(), saved.getBundleName(), provider);

        return BundleResponse.from(saved, "USD");
    }

    @Transactional
    @CacheEvict(value = "bundles", allEntries = true)
    public BundleResponse updateBundle(String provider, String bundleCode, UpdateBundleRequest request) {
        BundleProduct bundle = bundleRepository
                .findByProviderCodeAndBundleCode(provider.toUpperCase(), bundleCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bundle not found: " + provider + " - " + bundleCode));

        if (request.getBundleName() != null) bundle.setBundleName(request.getBundleName());
        if (request.getCategory() != null) bundle.setCategory(request.getCategory());
        if (request.getPriceUsdCents() != null) bundle.setPriceUsdCents(request.getPriceUsdCents());
        if (request.getPriceZwgCents() != null) bundle.setPriceZwgCents(request.getPriceZwgCents());
        if (request.getDataVolumeMb() != null) bundle.setDataVolumeMb(request.getDataVolumeMb());
        if (request.getValidityDays() != null) bundle.setValidityDays(request.getValidityDays());
        if (request.getValidityHours() != null) bundle.setValidityHours(request.getValidityHours());
        if (request.getIncludesSms() != null) bundle.setIncludesSms(request.getIncludesSms());
        if (request.getIncludesVoiceMinutes() != null) bundle.setIncludesVoiceMinutes(request.getIncludesVoiceMinutes());
        if (request.getDescription() != null) bundle.setDescription(request.getDescription());
        if (request.getIsActive() != null) bundle.setIsActive(request.getIsActive());

        bundle.setUpdatedAt(LocalDateTime.now());

        BundleProduct saved = bundleRepository.save(bundle);
        log.info("[BUNDLE] Updated bundle: {} - {} for provider: {}",
                saved.getBundleCode(), saved.getBundleName(), provider);

        return BundleResponse.from(saved, "USD");
    }

    @Transactional
    @CacheEvict(value = "bundles", allEntries = true)
    public Map<String, Object> processExcelUpload(MultipartFile file, String provider, String version) {
        Map<String, Object> result = new HashMap<>();
        List<BundleProduct> updated = new ArrayList<>();
        List<BundleProduct> created = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> processedCodes = new HashSet<>();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();

            int rowNum = 0;
            while (rows.hasNext() && rowNum < 5) {
                rows.next();
                rowNum++;
            }

            while (rows.hasNext()) {
                Row row = rows.next();
                try {
                    BundleProduct bundle = parseRow(row, provider, version);
                    if (bundle != null && bundle.getBundleCode() != null && !bundle.getBundleCode().isEmpty()) {
                        if (processedCodes.contains(bundle.getBundleCode())) {
                            errors.add("Row " + (row.getRowNum() + 1) + ": Duplicate bundle code '" + bundle.getBundleCode() + "' in same file");
                            continue;
                        }

                        Optional<BundleProduct> existing = bundleRepository
                                .findByProviderCodeAndBundleCode(provider.toUpperCase(), bundle.getBundleCode());

                        if (existing.isPresent()) {
                            BundleProduct existingBundle = existing.get();
                            existingBundle.setBundleName(bundle.getBundleName());
                            existingBundle.setCategory(bundle.getCategory());
                            existingBundle.setPriceUsdCents(bundle.getPriceUsdCents());
                            existingBundle.setPriceZwgCents(bundle.getPriceZwgCents());
                            existingBundle.setDataVolumeMb(bundle.getDataVolumeMb());
                            existingBundle.setValidityDays(bundle.getValidityDays());
                            existingBundle.setValidityHours(bundle.getValidityHours());
                            existingBundle.setIncludesSms(bundle.getIncludesSms());
                            existingBundle.setIncludesVoiceMinutes(bundle.getIncludesVoiceMinutes());
                            existingBundle.setDescription(bundle.getDescription());
                            existingBundle.setIsActive(true);
                            existingBundle.setUpdatedAt(LocalDateTime.now());
                            existingBundle.setSourceFile(file.getOriginalFilename());
                            existingBundle.setSourceVersion(version);
                            updated.add(existingBundle);
                            processedCodes.add(bundle.getBundleCode());
                        } else {
                            bundle.setCreatedAt(LocalDateTime.now());
                            bundle.setProviderName(getProviderName(provider));
                            bundle.setSourceFile(file.getOriginalFilename());
                            bundle.setSourceVersion(version);
                            created.add(bundle);
                            processedCodes.add(bundle.getBundleCode());
                        }
                    }
                } catch (Exception e) {
                    errors.add("Row " + (row.getRowNum() + 1) + ": " + e.getMessage());
                    log.error("Error parsing row {}: {}", row.getRowNum() + 1, e.getMessage());
                }
            }

            if (!updated.isEmpty()) bundleRepository.saveAll(updated);
            if (!created.isEmpty()) bundleRepository.saveAll(created);

            int deactivated = 0;
            if (!processedCodes.isEmpty()) {
                deactivated = bundleRepository.deactivateOldBundles(
                        provider.toUpperCase(), new ArrayList<>(processedCodes), LocalDateTime.now());
            }

            result.put("message", "Bundles processed successfully");
            result.put("provider", provider);
            result.put("created", created.size());
            result.put("updated", updated.size());
            result.put("total", created.size() + updated.size());
            result.put("deactivated", deactivated);
            result.put("errors", errors);
            result.put("timestamp", LocalDateTime.now());

        } catch (IOException e) {
            log.error("Failed to process Excel file: {}", e.getMessage());
            throw new RuntimeException("Failed to process Excel file: " + e.getMessage());
        }

        return result;
    }

    private BundleProduct parseRow(Row row, String provider, String version) {
        if (isEmptyRow(row)) return null;

        BundleProduct bundle = new BundleProduct();
        bundle.setProviderCode(provider.toUpperCase());
        bundle.setSourceVersion(version);

        try {
            Cell mapNameCell = row.getCell(0, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String mapName = "";
            if (mapNameCell.getCellType() == CellType.STRING) {
                mapName = mapNameCell.getStringCellValue().trim();
            } else if (mapNameCell.getCellType() == CellType.NUMERIC) {
                mapName = String.valueOf((int) mapNameCell.getNumericCellValue());
            }

            Cell categoryCell = row.getCell(1, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String category = "";
            if (categoryCell.getCellType() == CellType.STRING) {
                category = categoryCell.getStringCellValue().trim();
            }

            Cell codeCell = row.getCell(2, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String bundleCode = null;
            if (codeCell.getCellType() == CellType.STRING) {
                bundleCode = codeCell.getStringCellValue().trim();
            } else if (codeCell.getCellType() == CellType.NUMERIC) {
                bundleCode = String.valueOf((int) codeCell.getNumericCellValue());
            }

            if (bundleCode == null || bundleCode.isEmpty()) {
                return null;
            }
            bundle.setBundleCode(bundleCode);

            Cell nameCell = row.getCell(3, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String bundleDesc = "";
            if (nameCell.getCellType() == CellType.STRING) {
                bundleDesc = nameCell.getStringCellValue().trim();
            }

            if (mapName != null && !mapName.isEmpty()) {
                bundle.setBundleName(mapName);
            } else if (bundleDesc != null && !bundleDesc.isEmpty()) {
                bundle.setBundleName(bundleDesc);
            } else {
                bundle.setBundleName(bundleCode);
            }

            if (category != null && !category.isEmpty()) {
                bundle.setCategory(category);
            }

            Cell validityCell = row.getCell(5, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            if (validityCell.getCellType() == CellType.STRING) {
                String validity = validityCell.getStringCellValue().trim();
                if (!validity.isEmpty()) {
                    parseValidity(validity, bundle);
                }
            }

            Cell volumeCell = row.getCell(6, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            if (volumeCell.getCellType() == CellType.NUMERIC) {
                double volume = volumeCell.getNumericCellValue();
                if (volume > 0) {
                    bundle.setDataVolumeMb((int) volume);
                }
            }

            Cell priceCell = row.getCell(7, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            if (priceCell.getCellType() == CellType.NUMERIC) {
                double price = priceCell.getNumericCellValue();
                if (price > 0) {
                    bundle.setPriceUsdCents((int) Math.round(price * 100));
                }
            }

            bundle.setIsActive(true);
            bundle.setDisplayOrder(row.getRowNum());

        } catch (Exception e) {
            log.error("Error parsing bundle row {}: {}", row.getRowNum() + 1, e.getMessage());
            throw new RuntimeException("Failed to parse bundle row: " + e.getMessage(), e);
        }

        return bundle;
    }

    private void parseValidity(String validityStr, BundleProduct bundle) {
        if (validityStr == null || validityStr.isEmpty()) return;
        String lower = validityStr.toLowerCase().trim();
        String numbers = lower.replaceAll("[^0-9]", "");

        if (lower.contains("day") || lower.contains("daily") || lower.contains("d ") ||
                lower.contains("week") || lower.contains("month")) {
            if (!numbers.isEmpty()) {
                try {
                    int value = Integer.parseInt(numbers);
                    if (lower.contains("week") && value <= 4) {
                        bundle.setValidityDays(value * 7);
                    } else if (lower.contains("month") && value <= 12) {
                        bundle.setValidityDays(value * 30);
                    } else {
                        bundle.setValidityDays(value);
                    }
                } catch (NumberFormatException e) {
                    if (lower.contains("week")) bundle.setValidityDays(7);
                    else if (lower.contains("month")) bundle.setValidityDays(30);
                    else bundle.setValidityDays(1);
                }
            } else {
                if (lower.contains("week")) bundle.setValidityDays(7);
                else if (lower.contains("month")) bundle.setValidityDays(30);
                else bundle.setValidityDays(1);
            }
        } else if (lower.contains("hour") || lower.contains("hr")) {
            if (!numbers.isEmpty()) {
                try {
                    bundle.setValidityHours(Integer.parseInt(numbers));
                } catch (NumberFormatException e) {
                    bundle.setValidityHours(1);
                }
            } else {
                bundle.setValidityHours(1);
            }
        }
    }

    private boolean isEmptyRow(Row row) {
        if (row == null) return true;
        for (int i = 0; i < 3; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null) {
                if (cell.getCellType() == CellType.STRING) {
                    String value = cell.getStringCellValue().trim();
                    if (!value.isEmpty()) return false;
                } else if (cell.getCellType() == CellType.NUMERIC) {
                    return false;
                }
            }
        }
        return true;
    }

    private String getProviderName(String providerCode) {
        switch (providerCode.toUpperCase()) {
            case "ECONET": return "Econet Wireless";
            case "NETONE": return "NetOne";
            case "TELECEL": return "Telecel";
            default: return providerCode;
        }
    }

    @Transactional
    @CacheEvict(value = "bundles", allEntries = true)
    public void deactivateBundle(String provider, String bundleCode) {
        BundleProduct bundle = bundleRepository
                .findByProviderCodeAndBundleCode(provider.toUpperCase(), bundleCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bundle not found: " + provider + " - " + bundleCode));

        bundle.setIsActive(false);
        bundle.setUpdatedAt(LocalDateTime.now());
        bundleRepository.save(bundle);

        log.info("[BUNDLE] Deactivated bundle: {} for provider: {}", bundleCode, provider);
    }

    @Transactional
    @CacheEvict(value = "bundles", allEntries = true)
    public int bulkUpdateStatus(String provider, BulkStatusUpdateRequest request) {
        int count = 0;
        for (String bundleCode : request.getBundleCodes()) {
            Optional<BundleProduct> opt = bundleRepository
                    .findByProviderCodeAndBundleCode(provider.toUpperCase(), bundleCode);

            if (opt.isPresent()) {
                BundleProduct bundle = opt.get();
                bundle.setIsActive(request.isActive());
                bundle.setUpdatedAt(LocalDateTime.now());
                bundleRepository.save(bundle);
                count++;
            }
        }
        log.info("[BUNDLE] Bulk status update for provider: {} - {} bundles set to active={}",
                provider, count, request.isActive());
        return count;
    }

    /** Every bundle of a provider, switched on or off, for Platform setup → Bundles (NetOne refreshed first). */
    public List<BundleResponse> getAllBundles(String provider, String currency) {
        String p = provider.toUpperCase();
        String ccy = currency == null ? "USD" : currency.toUpperCase();
        // NetOne: the bundle manager shows NetOne's live list, the same one customers see.
        if ("NETONE".equals(p)) { netOneLiveCache.remove("ZWG".equals(ccy) || "ZWL".equals(ccy) ? "ZWG" : "USD"); return netOneLive(ccy); }
        return bundleRepository.findAll().stream()
                .filter(b -> p.equals(b.getProviderCode()))
                .filter(b -> !"NETONE".equals(p) || ("ZWG".equals(ccy) ? b.getPriceZwgCents() != null : b.getPriceUsdCents() != null))
                .sorted(Comparator.comparing((BundleProduct b) -> b.getDisplayOrder() == null ? Integer.MAX_VALUE : b.getDisplayOrder())
                        .thenComparing(BundleProduct::getBundleCode))
                .map(b -> BundleResponse.from(b, ccy))
                .toList();
    }

    private void syncNetOneIfDue() {
        if (System.currentTimeMillis() - netOneSyncedAt < NETONE_SYNC_EVERY_MS) return;
        try { syncNetOne(); } catch (RuntimeException e) { log.warn("[NETONE] Catalogue refresh failed: {}", e.getMessage()); }
    }

    /**
     * Copies NetOne's live USD and ZWG data bundle catalogues into the bundle table: prices, names, volumes and
     * validity are refreshed; a bundle seen for the first time is added switched off; switches are never changed.
     */
    @CacheEvict(value = "bundles", allEntries = true)
    @Transactional
    public synchronized Map<String, Object> syncNetOne() {
        Map<String, Object> out = new LinkedHashMap<>();
        int added = 0, updated = 0;
        LocalDateTime now = LocalDateTime.now();
        for (String ccy : List.of("USD", "ZWG")) {
            List<zw.co.payport.gateway.service.adaptor.netone.NetOneDataBundleService.DataBundle> live = netOneData.fetchBundles(ccy);
            out.put(ccy, live.size());
            int order = 0;
            for (var d : live) {
                order++;
                if (d.bundleCode() == null || d.bundleCode().isBlank()) continue;
                Optional<BundleProduct> found = bundleRepository.findByProviderCodeAndBundleCode("NETONE", d.bundleCode());
                BundleProduct b = found.orElseGet(() -> {
                    BundleProduct n = new BundleProduct();
                    n.setProviderCode("NETONE");
                    n.setProviderName("NetOne");
                    n.setBundleCode(d.bundleCode());
                    n.setIsActive(false);
                    n.setCategory("Data Bundles");
                    n.setCreatedAt(now);
                    n.setSourceFile("NETONE_API");
                    return n;
                });
                if (found.isEmpty()) added++; else updated++;
                b.setBundleName(d.name() == null || d.name().isBlank() ? d.bundleCode() : d.name());
                if (d.validity() != null && !d.validity().isBlank()) b.setDescription(d.validity());
                int cents = (int) Math.min(Integer.MAX_VALUE, Math.max(0, d.amountCents()));
                if ("ZWG".equals(ccy)) b.setPriceZwgCents(cents); else b.setPriceUsdCents(cents);
                Integer mb = megabytes(d.dataSize());
                if (mb != null) b.setDataVolumeMb(mb);
                Integer days = days(d.validity());
                if (days != null) b.setValidityDays(days);
                if (b.getDisplayOrder() == null) b.setDisplayOrder(order);
                b.setUpdatedAt(now);
                bundleRepository.save(b);
            }
        }
        netOneSyncedAt = System.currentTimeMillis();
        if (added + updated == 0) out.put("message", "NetOne returned no bundles. Check the NetOne connection and the gateway log (NETONE-DATA).");
        out.put("added", added);
        out.put("updated", updated);
        log.info("[NETONE] Catalogue refreshed: {}", out);
        return out;
    }

    static Integer megabytes(String size) {
        if (size == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(GB|MB|G|M)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(size);
        if (!m.find()) return null;
        double v = Double.parseDouble(m.group(1));
        return (int) Math.round(m.group(2).toUpperCase().startsWith("G") ? v * 1024 : v);
    }

    static Integer days(String validity) {
        if (validity == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+)\\s*(day|days|d\\b|week|weeks|month|months)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(validity);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group(1));
        String u = m.group(2).toLowerCase();
        return u.startsWith("week") ? n * 7 : u.startsWith("month") ? n * 30 : n;
    }

    public List<BundleProduct> getActiveBundlesEntity(String provider, String currency, String category) {
        if (category != null && !category.isEmpty()) {
            return bundleRepository.findByProviderCodeAndCategoryAndIsActiveTrueOrderByDisplayOrder(
                    provider.toUpperCase(), category);
        }
        return bundleRepository.findByProviderCodeAndIsActiveTrueOrderByDisplayOrder(provider.toUpperCase());
    }
}
