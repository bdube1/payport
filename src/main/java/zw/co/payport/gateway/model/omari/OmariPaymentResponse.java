package zw.co.payport.gateway.model.omari;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OmariPaymentResponse {
    private boolean error;
    private String message;
    @JsonProperty("responseCode")
    private String responseCode;
    @JsonProperty("paymentReference")
    private String paymentReference;
    @JsonProperty("debitReference")
    private String debitReference;
}
