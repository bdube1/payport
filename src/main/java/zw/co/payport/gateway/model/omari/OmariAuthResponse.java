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
public class OmariAuthResponse {
    private boolean error;
    private String message;
    @JsonProperty("responseCode")
    private String responseCode;
    @JsonProperty("otpReference")
    private String otpReference;
}
