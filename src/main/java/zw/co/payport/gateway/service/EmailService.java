package zw.co.payport.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Staff password resets, and payment confirmation receipts for the
 * Merchants Module. Other customer notifications (SMS-based) go through
 * SmsNotificationService instead.
 *
 * Requires SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD to be set (see
 * .env.example). Without them, the app still starts normally -- this only
 * fails when a reset email is actually requested, which is the right
 * failure mode: a loud, specific error beats a silently undelivered email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${payport.email.from}")
    private String fromAddress;

    @Value("${payport.website.base-url}")
    private String websiteBaseUrl;

    @Value("${payport.email.reset-link-base}")
    private String resetLinkBase;

    public void sendPasswordReset(String toEmail, String fullName, String token) {
        String link = resetLinkBase + "?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Payport Ops Console -- Password Reset");
        message.setText(
                "Hi " + (fullName != null ? fullName : "") + ",\n\n" +
                "A password reset was requested for your Payport Ops Console account.\n\n" +
                "Reset your password here (this link expires in 1 hour):\n" + link + "\n\n" +
                "If you didn't request this, you can safely ignore this email -- your password will not be changed.\n\n" +
                "-- Payport"
        );

        try {
            mailSender.send(message);
            log.info("[EMAIL] Password reset email sent to {}", toEmail);
        } catch (Exception e) {
            // Don't let a mail server outage take down the reset flow with a
            // generic 500 -- surface something the ops team can actually act on.
            log.error("[EMAIL] Failed to send password reset email to {}: {}", toEmail, e.getMessage(), e);
            throw new RuntimeException("Could not send reset email. Contact an administrator.");
        }
    }

    /**
     * Merchant and agent portal mail (welcome with a temporary password, password reset link).
     * Best effort: returns false when it could not be sent, so the caller can show the value to ops.
     */
    public boolean sendPortalMail(String toEmail, String subject, String text) {
        if (toEmail == null || toEmail.isBlank()) return false;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("[EMAIL] Portal mail to {} not sent: {}", toEmail, e.getMessage());
            return false;
        }
    }

    /**
     * Payment confirmation receipt -- deliberately best-effort. A failed
     * confirmation email must never cause the underlying payment to look
     * like it failed; log and move on, don't throw.
     */
    public void sendPaymentConfirmation(String toEmail, String merchantName, String amountFormatted, String reference) {
        if (toEmail == null || toEmail.isBlank()) return;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Payment confirmation -- " + merchantName);
        message.setText(
                "Your payment has been confirmed.\n\n" +
                "Paid to: " + merchantName + "\n" +
                "Amount: " + amountFormatted + "\n" +
                "Reference: " + reference + "\n\n" +
                "-- Payport"
        );

        try {
            mailSender.send(message);
            log.info("[EMAIL] Payment confirmation sent to {} for {}", toEmail, reference);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send payment confirmation to {} for {}: {}", toEmail, reference, e.getMessage(), e);
        }
    }

    /**
     * Ops requesting something more from a merchant application -- a
     * missing KYC document, a clarification, etc. Best-effort, same as
     * the other notification emails here: a failed email must not block
     * the underlying status change (the applicant can still see the
     * request on their tracking page even if this email never arrives).
     */
    public void sendMerchantApplicationReceived(String toEmail, String businessName, String applicationReference) {
        if (toEmail == null || toEmail.isBlank()) return;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Your Payport merchant application — " + applicationReference);
        message.setText(
                "Hi,\n\n" +
                "Thanks for applying to accept payments with Payport, " + businessName + ".\n\n" +
                "Your application reference is: " + applicationReference + "\n\n" +
                "Keep this reference -- you can use it any time to check your application status, without needing to log in, here:\n" +
                websiteBaseUrl + "/merchants/track?ref=" + applicationReference + "\n\n" +
                "We'll also email you directly if we need anything further from you.\n\n" +
                "-- Payport"
        );

        try {
            mailSender.send(message);
            log.info("[EMAIL] Application received confirmation sent to {} for {} ({})", toEmail, businessName, applicationReference);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send application confirmation to {} for {}: {}", toEmail, businessName, e.getMessage(), e);
        }
    }

    public void sendMerchantInfoRequest(String toEmail, String businessName, String note) {
        if (toEmail == null || toEmail.isBlank()) return;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Action needed on your Payport merchant application");
        message.setText(
                "Hi,\n\n" +
                "We're reviewing your application for " + businessName + " and need a bit more from you before we can continue:\n\n" +
                note + "\n\n" +
                "You can respond and check your application status any time by logging into the merchant dashboard.\n\n" +
                "-- Payport"
        );

        try {
            mailSender.send(message);
            log.info("[EMAIL] Merchant info request sent to {} for {}", toEmail, businessName);
        } catch (Exception e) {
            log.error("[EMAIL] Failed to send merchant info request to {} for {}: {}", toEmail, businessName, e.getMessage(), e);
        }
    }
}
