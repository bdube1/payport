package zw.co.payport.gateway.model.omari;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OmariPaymentRequest {
    private String msisdn;
    private String reference;
    private String otp;
}
