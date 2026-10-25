package zw.co.payport.gateway.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.payport.gateway.entity.BundleProduct;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BundleResponse {
    private String providerCode;
    private String bundleCode;
    private String bundleName;
    private String category;
    private String description;

    // Price (formatted for display)
    private String price;
    private Double priceValue;
    private String currency;

    // Bundle details
    private String dataVolume;
    private Integer dataVolumeMb;
    private String validity;
    private Integer validityDays;
    private Integer validityHours;

    // Features
    private Integer includesSms;
    private Integer includesVoiceMinutes;
    private String peakData;
    private String offpeakData;

    // Display
    private String popularTag;
    private Integer displayOrder;
    /** Switched on in Platform setup → Bundles (customers see only these). */
    private Boolean isActive;

    // Additional features map
    private Map<String, Object> features;

    public static BundleResponse from(BundleProduct bundle, String currency) {
        BundleResponseBuilder builder = BundleResponse.builder()
                .providerCode(bundle.getProviderCode())
                .bundleCode(bundle.getBundleCode())
                .bundleName(bundle.getBundleName())
                .category(bundle.getCategory())
                .description(bundle.getDescription())
                .dataVolume(bundle.getDataVolumeDisplay())
                .dataVolumeMb(bundle.getDataVolumeMb())
                .validity(bundle.getValidityDisplay())
                .validityDays(bundle.getValidityDays())
                .validityHours(bundle.getValidityHours())
                .includesSms(bundle.getIncludesSms())
                .includesVoiceMinutes(bundle.getIncludesVoiceMinutes())
                .popularTag(bundle.getPopularTag())
                .displayOrder(bundle.getDisplayOrder())
                .isActive(Boolean.TRUE.equals(bundle.getIsActive()));

        // Set price based on currency
        if ("USD".equalsIgnoreCase(currency) && bundle.getPriceUsdCents() != null) {
            double priceValue = bundle.getPriceUsdCents() / 100.0;
            builder.price(String.format("$%.2f", priceValue));
            builder.priceValue(priceValue);
            builder.currency("USD");
        } else if (("ZWG".equalsIgnoreCase(currency) || "ZWL".equalsIgnoreCase(currency))
                && bundle.getPriceZwgCents() != null) {
            double priceValue = bundle.getPriceZwgCents() / 100.0;
            builder.price(String.format("ZiG %.2f", priceValue));
            builder.priceValue(priceValue);
            builder.currency("ZWG");
        } else {
            // Fallback to USD
            double priceValue = bundle.getPriceUsdCents() != null ?
                    bundle.getPriceUsdCents() / 100.0 : 0.0;
            builder.price(String.format("$%.2f", priceValue));
            builder.priceValue(priceValue);
            builder.currency("USD");
        }

        return builder.build();
    }

    private static String formatDataSize(int mb) {
        if (mb >= 1024) {
            return String.format("%.1f GB", mb / 1024.0);
        }
        return mb + " MB";
    }
}