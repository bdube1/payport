package zw.co.payport.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import zw.co.payport.gateway.entity.Transaction;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsNotificationService {

    @Value("${payport.sms.africas-talking.api-key:REPLACE_WITH_REAL_KEY}")
    private String apiKey;

    @Value("${payport.sms.africas-talking.username:sandbox}")
    private String username;

    @Value("${payport.sms.africas-talking.sender-id:}")
    private String senderId;

    @Value("${payport.sms.africas-talking.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void sendPaymentPending(Transaction tx, String paymentMethod) {
        String message = String.format(
                "PayPort: %s payment of %s initiated. Please approve the prompt on your phone. Ref: %s",
                paymentMethod, tx.getAmountFormatted(), tx.getReference()
        );
        notify(tx, message, "PAYMENT_PENDING");
    }

    @Async
    public void sendPaymentConfirmed(Transaction tx) {
        String message = String.format(
                "PayPort: Payment of %s received. Processing your %s now... Ref: %s",
                tx.getAmountFormatted(),
                tx.getProductType() != null ? tx.getProductType().getName() : "service",
                tx.getReference()
        );
        notify(tx, message, "PAYMENT_CONFIRMED");
    }

    @Async
    public void sendPaymentFailed(Transaction tx, String reason) {
        String message = String.format(
                "PayPort: Payment of %s was not successful. No money was deducted. Ref: %s. Contact support if needed.",
                tx.getAmountFormatted(), tx.getReference()
        );
        notify(tx, message, "PAYMENT_FAILED");
    }

    @Async
    public void sendDeliverySuccess(Transaction tx) {
        String productName = tx.getProductType() != null ? tx.getProductType().getName() : "service";
        String message;

        if (tx.getProductType() != null &&
                (tx.getProductType().getCode().startsWith("ZESA") ||
                        "ZESA_TOKEN".equals(tx.getProductType().getCode()))) {
            message = formatZesaTokenMessage(tx);
        } else {
            message = String.format(
                    "PayPort: %s of %s sent to %s. Ref: %s. Thank you!",
                    productName,
                    tx.getAmountFormatted(),
                    maskRecipient(tx.getRecipientIdentifier()),
                    tx.getReference()
            );
        }

        notify(tx, message, "COMPLETED");
    }

    /**
     * Format ZESA token message with support for multiple tokens (Key Change Tokens)
     * Per eSolutions spec v2.1:
     * - Tokens must be presented on 1 receipt strictly in the given order
     * - Customer should load strictly in the same order
     * - Tokens displayed without repeating "Token:" word on each line
     */
    private String formatZesaTokenMessage(Transaction tx) {
        try {
            String fulfilmentData = tx.getFulfilmentData();
            if (fulfilmentData == null || fulfilmentData.isBlank()) {
                log.warn("[ZESA_SMS] No fulfilment data for ref: {}", tx.getReference());
                return fallbackMessage(tx);
            }

            JsonNode root = objectMapper.readTree(fulfilmentData);
            StringBuilder sms = new StringBuilder();

            // Get currency
            String currency = tx.getCurrency() != null ? tx.getCurrency() : "USD";

            // Extract values
            String tokenRaw = root.has("tokenRaw") ? root.get("tokenRaw").asText() : "";
            String meterNumber = root.has("utilityAccount") ? root.get("utilityAccount").asText() : tx.getRecipientIdentifier();

            // Energy amount (net amount from token position 4)
            double energyAmount = root.has("energyAmount") ? root.get("energyAmount").asDouble() : 0.0;

            // kWh units for display
            String kwh = root.has("kwh") ? root.get("kwh").asText() : "0.00";

            // Debt amount from arrears
            double debtAmount = root.has("debtAmount") ? root.get("debtAmount").asDouble() : 0.0;

            // REA Levy from fixed charges
            double reaAmount = root.has("reaLevyAmount") ? root.get("reaLevyAmount").asDouble() : 0.0;

            // VAT amount from token
            double vatAmount = root.has("vatAmount") ? root.get("vatAmount").asDouble() : 0.0;

            // Parse multiple token numbers
            List<String> tokens = parseTokenNumbers(tokenRaw);

            if (tokens.isEmpty()) {
                log.warn("[ZESA_SMS] No tokens parsed for ref: {}", tx.getReference());
                return fallbackMessage(tx);
            }

            // Calculate Total Amount = Energy + Debt + REA + VAT
            double totalAmount = energyAmount + debtAmount + reaAmount + vatAmount;

            // Amount tendered
            double tenderedAmount = tx.getAmountCents() / 100.0;

            // Line 1: First token with "Token:" prefix
            if (!tokens.isEmpty()) {
                sms.append("Token: ").append(tokens.get(0)).append("\n");
            }

            // Subsequent tokens without "Token:" prefix (just the number)
            for (int i = 1; i < tokens.size(); i++) {
                sms.append(tokens.get(i)).append("\n");
            }

            // Meter number
            sms.append("Meter: ").append(meterNumber).append("\n");

            // KwH
            sms.append("KwH: ").append(formatAmount(parseDouble(kwh))).append("\n");

            // Energy Charge
            sms.append("Energy: ").append(currency).append(" ").append(formatAmount(energyAmount)).append("\n");

            // Debt recovered
            if (debtAmount > 0) {
                sms.append("Debt: ").append(currency).append(" ").append(formatAmount(debtAmount)).append("\n");
            } else {
                sms.append("Debt: ").append(currency).append(" 0.00\n");
            }

            // REA Levy
            if (reaAmount > 0) {
                sms.append("REA: ").append(currency).append(" ").append(formatAmount(reaAmount)).append("\n");
            } else {
                sms.append("REA: ").append(currency).append(" 0.00\n");
            }

            // VAT
            if (vatAmount > 0) {
                sms.append("VAT: ").append(currency).append(" ").append(formatAmount(vatAmount)).append("\n");
            } else {
                sms.append("VAT: ").append(currency).append(" 0.00\n");
            }

            // Total Amount
            sms.append("Total Amt: ").append(currency).append(" ").append(formatAmount(totalAmount)).append("\n");

            // Amount Tendered
            sms.append("Tendered: ").append(currency).append(" ")
                    .append(formatAmount(tenderedAmount)).append("\n");

            // Date and time
            LocalDateTime completedAt = tx.getCompletedAt() != null ? tx.getCompletedAt() : LocalDateTime.now();
            sms.append(formatDateTimeWithSeconds(completedAt));

            // Instruction for multiple tokens
            if (tokens.size() > 1) {
                sms.append("\n").append("Please input tokens in the given order");
            }

            log.info("[ZESA_SMS] Formatted message for ref: {}, token count: {}, energy: {}, debt: {}, rea: {}, vat: {}, total: {}, tendered: {}",
                    tx.getReference(), tokens.size(), energyAmount, debtAmount, reaAmount, vatAmount, totalAmount, tenderedAmount);
            return sms.toString();

        } catch (Exception e) {
            log.error("[ZESA_SMS] Failed to format message for ref: {}, error: {}",
                    tx.getReference(), e.getMessage(), e);
            return fallbackMessage(tx);
        }
    }

    /**
     * Parse token numbers from tokenRaw (separated by '#')
     * Returns just the token numbers formatted with spaces
     */
    private List<String> parseTokenNumbers(String tokenRaw) {
        List<String> tokens = new ArrayList<>();

        if (tokenRaw == null || tokenRaw.isBlank()) {
            return tokens;
        }

        String[] tokenEntries = tokenRaw.split("#");

        for (String entry : tokenEntries) {
            String[] parts = entry.split("\\|");
            if (parts.length > 0) {
                tokens.add(formatTokenWithSpaces(parts[0]));
            }
        }

        return tokens;
    }

    /**
     * Safely parse a double from a string
     */
    private double parseDouble(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String formatAmount(double amount) {
        return String.format("%.2f", amount);
    }

    /**
     * Format token number with spaces every 4 digits
     * e.g., "55031098229229671976" -> "5503 1098 2292 2967 1976"
     */
    private String formatTokenWithSpaces(String token) {
        if (token == null || token.isBlank()) return "";
        String clean = token.replaceAll("\\s+", "");
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                formatted.append(" ");
            }
            formatted.append(clean.charAt(i));
        }
        return formatted.toString();
    }

    private String formatDateTimeWithSeconds(LocalDateTime dateTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm:ss");
        return dateTime.format(formatter);
    }

    private String formatAmountFromCents(Number amountCents) {
        if (amountCents == null) return "0.00";
        double dollars = amountCents.doubleValue() / 100.0;
        return String.format("%.2f", dollars);
    }

    private String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return String.format("%.2f", amount.doubleValue());
    }

    private String fallbackMessage(Transaction tx) {
        return String.format(
                "PayPort: ZESA Token for %s delivered! Amount: %s. Ref: %s. Please check receipt.",
                maskRecipient(tx.getRecipientIdentifier()),
                tx.getAmountFormatted(),
                tx.getReference()
        );
    }

    @Async
    public void sendFulfilmentDelayed(Transaction tx) {
        String message = String.format(
                "PayPort: Your payment was received but delivery is delayed. Our team is on it. Ref: %s. We will update you shortly.",
                tx.getReference()
        );
        notify(tx, message, "FULFILMENT_DELAYED");
    }

    @Async
    public void sendRefundInitiated(Transaction tx) {
        String message = String.format(
                "PayPort: A refund of %s has been initiated to your account. Please allow 24-48 hours. Ref: %s",
                tx.getAmountFormatted(), tx.getReference()
        );
        notify(tx, message, "REFUND_INITIATED");
    }

    /** Not tied to a Transaction, unlike everything else in this class -- a PIN reset has no payment context to reference. */
    @Async
    public void sendPinReset(String phone, String newPin) {
        String message = String.format(
                "PayPort: Your PIN has been reset. New PIN: %s. You'll be asked to set a new one when you next log in. If you didn't request this, contact support immediately.",
                newPin
        );
        send(phone, message, "PIN_RESET", "PIN_RESET");
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<zw.co.payport.gateway.whatsapp.WhatsAppSender> whatsApp;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * Every payment update goes by SMS; a payment made on WhatsApp is also answered in the WhatsApp chat it came
     * from (the customer is inside WhatsApp's 24-hour reply window, so no template is needed).
     */
    private void notify(Transaction tx, String message, String messageType) {
        send(tx.getCustomerPhone(), message, tx.getReference(), messageType);
        if (tx.getChannel() != zw.co.payport.gateway.enums.TransactionChannel.WHATSAPP || whatsApp == null) return;
        try {
            var sender = whatsApp.getIfAvailable();
            if (sender == null || !sender.configured()) return;
            String to = null;
            if (jdbc != null) {
                java.util.List<String> m = jdbc.queryForList("SELECT msisdn FROM ussd_sessions WHERE reference = ? ORDER BY updated_at DESC LIMIT 1", String.class, tx.getReference());
                if (!m.isEmpty()) to = m.get(0);
            }
            if (to == null) to = tx.getCustomerPhone();
            String err = sender.send(to.replaceAll("[^0-9]", ""), message);
            if (err != null) log.warn("[WHATSAPP] {} update for {} not sent: {}", messageType, tx.getReference(), err);
        } catch (RuntimeException e) {
            log.warn("[WHATSAPP] {} update for {} failed: {}", messageType, tx.getReference(), e.getMessage());
        }
    }

    /** A plain message (e.g. a card payment link for a USSD customer). */
    @Async
    public void sendText(String phone, String message, String reference, String messageType) {
        send(phone, message, reference, messageType);
    }

    private void send(String phone, String message, String reference, String messageType) {
        if (phone == null || phone.isBlank()) {
            log.debug("[SMS] No customer phone for {} - skipping SMS", reference);
            return;
        }

        String formattedPhone = formatPhoneNumber(phone);

        log.info("[SMS] Sending {} SMS to {} for ref: {}", messageType, maskPhone(formattedPhone), reference);
        log.info("[SMS] Message content:\n{}", message);

        if ("REPLACE_WITH_REAL_KEY".equals(apiKey)) {
            log.warn("[SMS] Africa's Talking API key not configured. SMS would have been sent with content above.");
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("apiKey", apiKey);
            headers.set("Accept", "application/json");

            StringBuilder body = new StringBuilder();
            body.append("username=").append(URLEncoder.encode(username, StandardCharsets.UTF_8));
            body.append("&to=").append(URLEncoder.encode(formattedPhone, StandardCharsets.UTF_8));
            body.append("&message=").append(URLEncoder.encode(message, StandardCharsets.UTF_8));

            if (senderId != null && !senderId.isBlank() && !"REPLACE_WITH_SENDER_ID".equals(senderId)) {
                body.append("&from=").append(URLEncoder.encode(senderId, StandardCharsets.UTF_8));
            }

            HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(baseUrl, HttpMethod.POST, entity, String.class);

            log.info("[SMS] Sent {} - HTTP {}", messageType, response.getStatusCode());

            if (response.getBody() != null && !response.getBody().isBlank()) {
                log.debug("[SMS] Response: {}", response.getBody());
            }

        } catch (Exception e) {
            log.error("[SMS] Failed to send {} SMS for {}: {}", messageType, reference, e.getMessage());
        }
    }

    private String formatPhoneNumber(String phone) {
        if (phone == null) return "";
        String cleaned = phone.replaceAll("\\s+", "");
        if (!cleaned.startsWith("+")) {
            cleaned = "+" + cleaned;
        }
        return cleaned;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }

    private String maskRecipient(String id) {
        if (id == null || id.length() < 6) return id;
        return id.substring(0, 3) + "****" + id.substring(id.length() - 3);
    }
}