package zw.co.payport.gateway.model.omari;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OmariQueryResponse {
    private boolean error;
    private String message;
    private String status;
    @JsonProperty("responseCode")
    private String responseCode;
    private String reference;
    private BigDecimal amount;
    private String currency;
    private String channel;
    @JsonProperty("paymentReference")
    private String paymentReference;
    @JsonProperty("debitReference")
    private String debitReference;
    private String created;
}
