package zw.co.payport.gateway.psp.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.agent.Agent;
import zw.co.payport.gateway.psp.agent.AgentRepository;
import zw.co.payport.gateway.psp.common.Pins;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.integration.IntegrationSpis;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.KycService;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.legacy.LegacyPayments;
import zw.co.payport.gateway.psp.merchant.MerchantControlService;
import zw.co.payport.gateway.psp.notify.NotificationService;
import zw.co.payport.gateway.psp.wallet.WalletService;
import zw.co.payport.gateway.psp.wallet.WalletTransfer;
import zw.co.payport.gateway.service.SmsNotificationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * WhatsApp channel. Deliberately read-mostly:
 *   1 Balance and 2 Mini statement: after a one-time code sent by SMS to the
 *     wallet number (valid 30 minutes). Wallet PINs are never typed into chat.
 *   3 Pay a merchant: by merchant number or by pasting a PayPort QR; returns a
 *     hosted checkout link where the customer approves with their wallet.
 *   4 Find an agent by district.
 * Money is never moved by chat message alone.
 */
@Service
public class WhatsAppBot {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppBot.class);
    private static final String CH = "WHATSAPP";

    private final ChatSessionRepository sessions;
    private final KycService kyc;
    private final WalletService wallets;
    private final AgentRepository agents;
    private final LegacyGateway legacy;
    private final LegacyPayments payments;
    private final MerchantControlService merchants;
    private final SmsNotificationService sms;
    private final List<IntegrationSpis.MessageChannel> channels;
    private final ObjectMapper om;

    @Value("${payport.consumer.base-url:https://app.payport.africa}")
    private String consumerBaseUrl = "https://app.payport.africa";

    public WhatsAppBot(ChatSessionRepository sessions, KycService kyc, WalletService wallets, AgentRepository agents,
                       LegacyGateway legacy, LegacyPayments payments, MerchantControlService merchants,
                       SmsNotificationService sms, List<IntegrationSpis.MessageChannel> channels, ObjectMapper om) {
        this.sessions = sessions;
        this.kyc = kyc;
        this.wallets = wallets;
        this.agents = agents;
        this.legacy = legacy;
        this.payments = payments;
        this.merchants = merchants;
        this.sms = sms;
        this.channels = channels;
        this.om = om;
    }

    /** Handles one inbound text and sends the reply. */
    @Transactional
    public String onMessage(String waId, String text) {
        String msisdn = "+" + waId.replaceAll("\\D", "");
        String in = text == null ? "" : text.trim();
        ChatSession s = sessions.findByChannelAndUserRef(CH, msisdn).orElseGet(() -> {
            ChatSession n = new ChatSession();
            n.setChannel(CH);
            n.setUserRef(msisdn);
            n.setState("IDLE");
            n.setData("{}");
            return n;
        });
        if (s.getExpiresAt() != null && s.getExpiresAt().isBefore(LocalDateTime.now()) && !"IDLE".equals(s.getState())) {
            s.setState("IDLE");
        }
        String reply;
        try {
            reply = step(s, msisdn, in);
        } catch (PspException e) {
            s.setState("IDLE");
            reply = e.getMessage() + "\n\nReply MENU for options.";
        } catch (Exception e) {
            s.setState("IDLE");
            log.info("[WHATSAPP] Could not handle message: {}", e.getMessage());
            reply = "Sorry, I didn't understand that. Reply MENU for options.";
        }
        s.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        sessions.save(s);
        send(msisdn, reply);
        return reply;
    }

    private String step(ChatSession s, String msisdn, String in) throws Exception {
        String lower = in.toLowerCase(Locale.ROOT);
        if (List.of("hi", "hello", "menu", "start", "0").contains(lower)) {
            s.setState("IDLE");
            return menu();
        }
        ObjectNode data = (ObjectNode) om.readTree(s.getData() == null ? "{}" : s.getData());
        switch (s.getState()) {
            case "AWAIT_OTP" -> {
                if (data.path("tries").asInt() >= 3) { s.setState("IDLE"); return "Too many wrong codes. Reply MENU to start again."; }
                if (!Pins.matches(in, data.path("otpHash").asText()) || LocalDateTime.parse(data.path("otpExpires").asText()).isBefore(LocalDateTime.now())) {
                    data.put("tries", data.path("tries").asInt() + 1);
                    s.setData(om.writeValueAsString(data));
                    return "That code is wrong or expired. Try again.";
                }
                data.put("verifiedUntil", LocalDateTime.now().plusMinutes(30).toString());
                data.remove("otpHash");
                s.setData(om.writeValueAsString(data));
                s.setState("IDLE");
                return run(data.path("intent").asText(), msisdn);
            }
            case "AWAIT_MERCHANT" -> {
                long merchantId;
                Long amount = null;
                String ccy = "USD";
                if (in.startsWith("000201")) {
                    EmvQr.Payload p = EmvQr.parse(in.replaceAll("\\s", ""));
                    merchantId = Long.parseLong(p.merchantRef().replaceAll("\\D", ""));
                    amount = p.amountMinor();
                    ccy = p.currency();
                } else {
                    merchantId = Long.parseLong(in.replaceAll("\\D", ""));
                }
                String name = legacy.merchant(merchantId).filter(LegacyGateway.MerchantSnapshot::isActive)
                        .map(LegacyGateway.MerchantSnapshot::businessName).orElseThrow(() -> PspException.notFound("Merchant", merchantId));
                data.put("merchantId", merchantId);
                data.put("merchantName", name);
                data.put("currency", ccy);
                s.setData(om.writeValueAsString(data));
                if (amount != null) return checkoutLink(s, msisdn, merchantId, amount, ccy, name);
                s.setState("AWAIT_AMOUNT");
                return "Paying " + name + ". How much (" + ccy + ")?";
            }
            case "AWAIT_AMOUNT" -> {
                long amount = UssdService.amount(in.replaceAll("[^0-9.]", ""));
                return checkoutLink(s, msisdn, data.path("merchantId").asLong(), amount, data.path("currency").asText("USD"), data.path("merchantName").asText());
            }
            case "AWAIT_DISTRICT" -> {
                s.setState("IDLE");
                List<Agent> list = agents.findByStatus("ACTIVE").stream()
                        .filter(a -> a.getDistrict() != null && a.getDistrict().equalsIgnoreCase(in)).limit(8).toList();
                if (list.isEmpty()) return "No agents found in " + in + " yet. Try a nearby district.";
                StringBuilder sb = new StringBuilder("Agents in " + in + ":");
                for (Agent a : list) sb.append("\n• ").append(a.getBusinessName()).append(" (code ").append(a.getAgentCode()).append(")");
                return sb.toString();
            }
            default -> {
                return switch (lower) {
                    case "1" -> secured(s, data, msisdn, "BALANCE");
                    case "2" -> secured(s, data, msisdn, "STATEMENT");
                    case "3" -> { s.setState("AWAIT_MERCHANT"); yield "Send the merchant number, or paste the text from their PayPort QR code."; }
                    case "4" -> { s.setState("AWAIT_DISTRICT"); yield "Which district are you in? (e.g. Harare, Bulawayo, Mutare)"; }
                    default -> menu();
                };
            }
        }
    }

    private String secured(ChatSession s, ObjectNode data, String msisdn, String intent) throws Exception {
        CustomerProfile c = kyc.byMsisdn(msisdn).orElseThrow(() -> PspException.notFound("Wallet for", msisdn));
        if (data.hasNonNull("verifiedUntil") && LocalDateTime.parse(data.path("verifiedUntil").asText()).isAfter(LocalDateTime.now())) {
            return run(intent, msisdn);
        }
        String otp = Refs.digits(6);
        data.put("otpHash", Pins.hashUnchecked(otp));
        data.put("otpExpires", LocalDateTime.now().plusMinutes(5).toString());
        data.put("tries", 0);
        data.put("intent", intent);
        s.setData(om.writeValueAsString(data));
        s.setState("AWAIT_OTP");
        sms.sendCustom(c.getMsisdn(), "PayPort: your WhatsApp verification code is " + otp + ". It expires in 5 minutes. Never share it.", "WA_OTP", "WA_OTP");
        return "We've sent a 6-digit code by SMS to your wallet number. Reply with the code.";
    }

    private String run(String intent, String msisdn) {
        CustomerProfile c = kyc.requireActive(msisdn);
        if ("STATEMENT".equals(intent)) {
            StringBuilder sb = new StringBuilder("Your last transactions:");
            for (WalletTransfer t : wallets.history(c.getCustomerRef(), 8)) {
                boolean out = c.getCustomerRef().equals(t.getFromRef());
                sb.append("\n").append(t.getCreatedAt().toLocalDate()).append("  ").append(out ? "−" : "+")
                        .append(NotificationService.money(t.getAmountMinor(), t.getCurrency())).append("  ").append(t.getType().replace('_', ' ').toLowerCase());
            }
            return sb.toString();
        }
        return "Your balances:\nUSD " + NotificationService.money(wallets.balance(c.getCustomerRef(), "USD"), "").trim()
                + "\nZiG " + NotificationService.money(wallets.balance(c.getCustomerRef(), "ZWG"), "").trim();
    }

    private String checkoutLink(ChatSession s, String msisdn, long merchantId, long amount, String ccy, String name) {
        merchants.assertCanCollect(merchantId, null, ccy, amount);
        long sessionId = payments.createCheckout(merchantId, "wa_" + Refs.ulid(), amount, ccy, "WhatsApp payment", msisdn);
        s.setState("IDLE");
        return "Pay " + NotificationService.money(amount, ccy) + " to " + name + " here:\n" + consumerBaseUrl + "/checkout/" + sessionId
                + "\nYou'll approve it on your phone with your wallet PIN.";
    }

    private static String menu() {
        return "PayPort on WhatsApp\n1 Balance\n2 Mini statement\n3 Pay a merchant\n4 Find an agent\n\nReply with a number.";
    }

    private void send(String msisdn, String text) {
        channels.stream().filter(c -> CH.equals(c.channel())).findFirst().ifPresent(c -> {
            try {
                c.send(msisdn, "BOT_REPLY", text);
            } catch (Exception e) {
                log.warn("[WHATSAPP] Reply to {} failed: {}", msisdn.substring(0, Math.min(7, msisdn.length())) + "***", e.getMessage());
            }
        });
    }
}
