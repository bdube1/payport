package zw.co.payport.gateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import java.time.LocalDateTime;

/**
 * Bundle Product Entity
 *
 * Stores product bundles from providers like Econet that don't have live APIs.
 * Populated via Excel uploads from the admin portal.
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "bundle_products", indexes = {
        @Index(name = "idx_bundle_provider", columnList = "provider_code,is_active"),
        @Index(name = "idx_bundle_code", columnList = "provider_code,bundle_code", unique = true)
})
public class BundleProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Provider identification
    @Column(name = "provider_code", nullable = false, length = 20)
    private String providerCode;  // 'ECONET', 'NETONE', etc.

    @Column(name = "provider_name", length = 50)
    private String providerName;   // 'Econet Wireless'

    // Bundle identification (from Excel)
    @Column(name = "bundle_code", nullable = false, length = 50)
    private String bundleCode;     // 'DDBUS1', 'PWBUS7', etc.

    @Column(name = "bundle_name", nullable = false, length = 200)
    private String bundleName;     // 'Daily 300MB + 30 SMS'

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "category", length = 50)
    private String category;       // 'DATA', 'SMS', 'VOICE', 'WHATSAPP_BUNDLES'

    // Pricing (store in cents - NEVER use double/float)
    @Column(name = "price_usd_cents")
    private Integer priceUsdCents;  // 150 = $1.50

    @Column(name = "price_zwg_cents")
    private Integer priceZwgCents;  // For Zimbabwe Gold

    // Bundle details
    @Column(name = "data_volume_mb")
    private Integer dataVolumeMb;   // Data in megabytes

    @Column(name = "validity_days")
    private Integer validityDays;   // 1, 7, 30

    @Column(name = "validity_hours")
    private Integer validityHours;  // For hourly bundles

    // Features (optional)
    @Column(name = "includes_sms")
    private Integer includesSms;     // Number of SMS included

    @Column(name = "includes_voice_minutes")
    private Integer includesVoiceMinutes;  // Voice minutes included

    @Column(name = "peak_data_mb")
    private Integer peakDataMb;      // For split bundles

    @Column(name = "offpeak_data_mb")
    private Integer offpeakDataMb;   // For split bundles

    // Display and status
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "popular_tag", length = 50)
    private String popularTag;      // 'BESTSELLER', 'NEW', 'LIMITED'

    // Additional features stored as JSON
    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;     // JSON string for custom features

    // Source tracking (which Excel file/version)
    @Column(name = "source_file", length = 255)
    private String sourceFile;

    @Column(name = "source_version", length = 50)
    private String sourceVersion;    // From Excel metadata

    // Timestamps
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;  // When price takes effect

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;    // For scheduled changes

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (isActive == null) isActive = true;
        if (displayOrder == null) displayOrder = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Helper methods
    public Double getPriceUsdAsDouble() {
        return priceUsdCents != null ? priceUsdCents / 100.0 : 0.0;
    }

    public Double getPriceZwgAsDouble() {
        return priceZwgCents != null ? priceZwgCents / 100.0 : 0.0;
    }

    public String getDataVolumeDisplay() {
        if (dataVolumeMb == null) return null;
        if (dataVolumeMb >= 1024) {
            return String.format("%.1f GB", dataVolumeMb / 1024.0);
        }
        return dataVolumeMb + " MB";
    }

    public String getValidityDisplay() {
        if (validityDays != null && validityDays > 0) {
            return validityDays + (validityDays == 1 ? " day" : " days");
        }
        if (validityHours != null && validityHours > 0) {
            return validityHours + (validityHours == 1 ? " hour" : " hours");
        }
        return null;
    }
}