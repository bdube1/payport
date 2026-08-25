package zw.co.payport.gateway.model.request;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
public class CreateBundleRequest {
    @NotBlank(message = "Bundle code is required")
    private String bundleCode;

    @NotBlank(message = "Bundle name is required")
    private String bundleName;

    @NotBlank(message = "Category is required")
    private String category;

    @NotNull(message = "USD price is required")
    private Integer priceUsdCents;

    private Integer priceZwgCents;
    private Integer dataVolumeMb;
    private Integer validityDays;
    private Integer validityHours;
    private Integer includesSms;
    private Integer includesVoiceMinutes;
    private String description;
    private Boolean isActive = true;
}