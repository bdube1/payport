package zw.co.payport.gateway.model.omari;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OmariAuthRequest {
    private String msisdn;
    private String reference;
    private BigDecimal amount;
    private String currency;
    private String channel;
}
