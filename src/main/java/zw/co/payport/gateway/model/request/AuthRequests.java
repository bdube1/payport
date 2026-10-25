package zw.co.payport.gateway.model.request;

import jakarta.validation.constraints.*;
import lombok.Data;

public class AuthRequests {

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 200, message = "Full name must be between 2 and 200 characters")
        private String fullName;

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone must be in international (E.164) format, e.g. +263771234567")
        private String phone;

        @NotBlank(message = "PIN is required")
        @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
        private String pin;

        /** National ID / passport number (the apps require it; kept optional here so older builds still register). */
        @Size(max = 40, message = "ID number is too long")
        @Pattern(regexp = "^$|^[A-Za-z0-9][A-Za-z0-9 /-]{3,39}$", message = "Enter a valid ID number")
        private String idNumber;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "Phone number is required")
        private String phone;

        @NotBlank(message = "PIN is required")
        @Pattern(regexp = "^\\d{4}$", message = "PIN must be exactly 4 digits")
        private String pin;

        /** Optional, deliberately — old app builds that don't send this keep
         *  logging in exactly as before, unrestricted. Only logins that send
         *  a deviceId participate in single-device enforcement. */
        private String deviceId;
    }

    @Data
    public static class OpsLoginRequest {
        @NotBlank(message = "Email is required")
        private String email;

        @NotBlank(message = "Password is required")
        private String password;
    }

    @Data
    public static class ForgotPasswordRequest {
        @NotBlank(message = "Email is required")
        private String email;
    }

    @Data
    public static class ResetPasswordRequest {
        @NotBlank(message = "Reset token is required")
        private String token;

        @NotBlank(message = "New password is required")
        private String newPassword;
    }
}
