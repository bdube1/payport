package zw.co.payport.gateway.model.request;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BundlePriceUpdateRequest {
    private Integer priceUsdCents;
    private Integer priceZwgCents;
    private LocalDateTime effectiveFrom;
    private String reason;
}