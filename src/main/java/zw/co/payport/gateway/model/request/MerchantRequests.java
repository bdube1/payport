package zw.co.payport.gateway.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

public class MerchantRequests {

    @Data
    public static class SignupRequest {
        @NotBlank(message = "Business name is required")
        private String businessName;

        @NotBlank(message = "Registration number is required")
        private String registrationNumber;

        private String businessCategory;

        @NotBlank(message = "Contact email is required")
        @Email(message = "Enter a valid email")
        private String contactEmail;

        @NotBlank(message = "Contact phone is required")
        private String contactPhone;

        /** Where the business operates (street, suburb, town), so Payport can visit it. */
        @NotBlank(message = "The business address is required")
        private String address;

        /** The first Merchant User (the applicant themselves) -- becomes OWNER on approval. */
        @NotBlank(message = "Your full name is required")
        private String ownerFullName;

        @NotBlank(message = "A password is required")
        private String password;
    }

    @Data
    public static class RejectRequest {
        @NotBlank(message = "A rejection reason is required")
        private String reason;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "Email is required")
        private String email;

        @NotBlank(message = "Password is required")
        private String password;
    }
}
