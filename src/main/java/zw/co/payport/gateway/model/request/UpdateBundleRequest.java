package zw.co.payport.gateway.model.request;

import lombok.Data;

@Data
public class UpdateBundleRequest {
    private String bundleName;
    private String category;
    private Integer priceUsdCents;
    private Integer priceZwgCents;
    private Integer dataVolumeMb;
    private Integer validityDays;
    private Integer validityHours;
    private Integer includesSms;
    private Integer includesVoiceMinutes;
    private String description;
    private Boolean isActive;
    private String reason;
}