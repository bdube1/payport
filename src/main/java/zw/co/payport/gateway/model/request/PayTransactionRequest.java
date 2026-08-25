package zw.co.payport.gateway.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class PayTransactionRequest {

    @NotBlank(message = "Reference is required")
    @Pattern(regexp = "^PP-(WEB|MOB|USD|API)-\\d{8}-\\d{6}$")
    private String reference;

    @NotBlank(message = "Payment method code is required")
    private String paymentMethodCode;  // ECOCASH | ONEMONEY | TELECASH

    // The phone that will receive the USSD push for payment approval
    @Pattern(regexp = "^\\+263\\d{9}$", message = "Phone must be E.164 format e.g. +263771234567")
    private String customerPhone;
}
