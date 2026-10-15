package zw.co.payport.gateway.psp.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.psp.agent.Agent;
import zw.co.payport.gateway.psp.agent.AgentRepository;
import zw.co.payport.gateway.psp.agent.AgentService;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;
import zw.co.payport.gateway.psp.fx.FxConversion;
import zw.co.payport.gateway.psp.fx.FxConversionService;
import zw.co.payport.gateway.psp.fx.FxRateService;
import zw.co.payport.gateway.psp.kyc.CustomerProfile;
import zw.co.payport.gateway.psp.kyc.KycService;
import zw.co.payport.gateway.psp.legacy.LegacyGateway;
import zw.co.payport.gateway.psp.notify.NotificationService;
import zw.co.payport.gateway.psp.platform.idempotency.IdempotencyService;
import zw.co.payport.gateway.psp.wallet.WalletService;
import zw.co.payport.gateway.psp.wallet.WalletTransfer;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * USSD menu engine (Africa's Talking style: the full input path arrives on
 * every hop as "1*2*0771234567*10*1234"). Stateless: the path IS the state.
 *
 * Customer menu: send money, pay merchant, cash out at agent, balance, mini
 * statement, convert USD/ZiG, change PIN, register. Agent menu: cash in,
 * float balance, transfer float. Every money movement shows the counterparty
 * name and fee before asking for the PIN (confirmation of payee), and runs
 * through the same wallet / agent services, limits and fraud checks as every
 * other channel. Final steps are idempotent per USSD session, so a gateway
 * retry never moves money twice.
 */
@Service
public class UssdService {

    private static final Logger log = LoggerFactory.getLogger(UssdService.class);

    private final KycService kyc;
    private final WalletService wallets;
    private final AgentService agentService;
    private final AgentRepository agents;
    private final FxRateService fx;
    private final FxConversionService fxConvert;
    private final LegacyGateway legacy;
    private final IdempotencyService idempotency;

    public UssdService(KycService kyc, WalletService wallets, AgentService agentService, AgentRepository agents,
                       FxRateService fx, FxConversionService fxConvert, LegacyGateway legacy, IdempotencyService idempotency) {
        this.kyc = kyc;
        this.wallets = wallets;
        this.agentService = agentService;
        this.agents = agents;
        this.fx = fx;
        this.fxConvert = fxConvert;
        this.legacy = legacy;
        this.idempotency = idempotency;
    }

    public String handle(String sessionId, String msisdn, String text) {
        String phone;
        try {
            phone = KycService.normaliseMsisdn(msisdn);
        } catch (PspException e) {
            return end("This service is available to Zimbabwean mobile numbers.");
        }
        List<String> in = text == null || text.isBlank() ? List.of() : Arrays.asList(text.split("\\*", -1));
        try {
            Optional<Agent> agent = agents.findByMsisdn(phone).filter(a -> "ACTIVE".equals(a.getStatus()));
            if (agent.isPresent()) {
                if (!in.isEmpty() && in.get(0).equals("9")) return customer(sessionId, phone, in.subList(1, in.size()));
                return agentMenu(sessionId, agent.get(), in);
            }
            return customer(sessionId, phone, in);
        } catch (PspException e) {
            return end(e.getMessage());
        } catch (Exception e) {
            log.error("[USSD] {} failed at '{}': {}", sessionId, text, e.getMessage());
            return end("Sorry, something went wrong. No money has moved. Please try again.");
        }
    }

    // ── Customer ─────────────────────────────────────────────────────────

    private String customer(String sid, String phone, List<String> in) {
        Optional<CustomerProfile> c = kyc.byMsisdn(phone);
        if (c.isEmpty()) return register(sid, phone, in);
        if (in.isEmpty()) {
            return con("PayPort\n1. Send money\n2. Pay merchant\n3. Cash out at agent\n4. Balance\n5. Mini statement\n6. Convert USD/ZiG\n7. Change PIN");
        }
        return switch (in.get(0)) {
            case "1" -> sendMoney(sid, phone, in);
            case "2" -> payMerchant(sid, phone, in);
            case "3" -> cashOut(sid, phone, in);
            case "4" -> balance(phone, c.get(), in);
            case "5" -> statement(c.get(), in);
            case "6" -> convert(sid, phone, c.get(), in);
            case "7" -> changePin(c.get(), in);
            default -> end("Invalid choice.");
        };
    }

    private String register(String sid, String phone, List<String> in) {
        if (in.isEmpty()) return con("Welcome to PayPort\n1. Open a wallet\n0. Exit");
        if (!"1".equals(in.get(0))) return end("Goodbye.");
        if (in.size() == 1) return con("Enter your first name");
        if (in.size() == 2) return con("Enter your surname");
        if (in.size() == 3) return con("Choose a 4-digit PIN");
        if (in.size() == 4) return con("Confirm your PIN");
        if (!in.get(3).equals(in.get(4))) return end("PINs did not match. Please dial again.");
        return once(sid, "register", () -> {
            CustomerProfile c = wallets.registerCustomer(phone, in.get(1), in.get(2), null, in.get(3), Actor.system("ussd"));
            return end("Welcome " + c.getFirstName() + "! Your PayPort wallet is ready. Visit an agent to cash in or verify your ID for higher limits.");
        });
    }

    private String sendMoney(String sid, String phone, List<String> in) {
        if (in.size() == 1) return currencyMenu();
        String ccy = ccy(in.get(1));
        if (in.size() == 2) return con("Enter recipient's mobile number");
        if (in.size() == 3) return con("Enter amount (" + label(ccy) + ")");
        long amount = amount(in.get(3));
        if (in.size() == 4) {
            CustomerProfile to = kyc.requireActive(in.get(2));
            long fee = wallets.charges("P2P", ccy, amount).customerFeeMinor() + wallets.transferTax(ccy, amount);
            return con("Send " + NotificationService.money(amount, ccy) + " to " + displayName(to) + "?\nFee " + NotificationService.money(fee, ccy)
                    + "\nEnter PIN to confirm");
        }
        return once(sid, "p2p", () -> {
            WalletService.TransferResult r = wallets.sendMoney(phone, in.get(2), ccy, amount, in.get(4), "USSD", sid);
            return end("Sent " + NotificationService.money(r.amountMinor(), ccy) + ". Ref " + shortRef(r.transferRef())
                    + ". New balance " + NotificationService.money(r.balanceAfterMinor(), ccy));
        });
    }

    private String payMerchant(String sid, String phone, List<String> in) {
        if (in.size() == 1) return currencyMenu();
        String ccy = ccy(in.get(1));
        if (in.size() == 2) return con("Enter merchant number");
        long merchantId = parseLong(in.get(2), "Invalid merchant number");
        if (in.size() == 3) return con("Enter amount (" + label(ccy) + ")");
        long amount = amount(in.get(3));
        if (in.size() == 4) {
            String name = legacy.merchant(merchantId).filter(LegacyGateway.MerchantSnapshot::isActive)
                    .map(LegacyGateway.MerchantSnapshot::businessName).orElseThrow(() -> PspException.notFound("Merchant", merchantId));
            return con("Pay " + NotificationService.money(amount, ccy) + " to " + name + "?\nEnter PIN to confirm");
        }
        return once(sid, "merchant", () -> {
            WalletService.TransferResult r = wallets.payMerchant(phone, merchantId, ccy, amount, in.get(4), "USSD", sid);
            return end("Paid " + NotificationService.money(r.amountMinor(), ccy) + ". Ref " + shortRef(r.transferRef()));
        });
    }

    private String cashOut(String sid, String phone, List<String> in) {
        if (in.size() == 1) return currencyMenu();
        String ccy = ccy(in.get(1));
        if (in.size() == 2) return con("Enter agent code");
        if (in.size() == 3) return con("Enter amount (" + label(ccy) + ")");
        long amount = amount(in.get(3));
        if (in.size() == 4) {
            Agent a = agentService.activeAgent(in.get(2));
            long fee = wallets.charges("CASH_OUT", ccy, amount).customerFeeMinor();
            return con("Withdraw " + NotificationService.money(amount, ccy) + " at " + a.getBusinessName() + "?\nFee "
                    + NotificationService.money(fee, ccy) + "\nEnter PIN to confirm");
        }
        return once(sid, "cashout", () -> {
            WalletService.TransferResult r = agentService.cashOut(phone, in.get(4), in.get(2), ccy, amount, "USSD");
            return end("Collect " + NotificationService.money(r.amountMinor(), ccy) + " from the agent. Ref " + shortRef(r.transferRef()));
        });
    }

    private String balance(String phone, CustomerProfile c, List<String> in) {
        if (in.size() == 1) return con("Enter PIN");
        wallets.verifyPin("CUSTOMER", c.getCustomerRef(), in.get(1));
        return end("Balance\nUSD " + major(wallets.balance(c.getCustomerRef(), "USD")) + "\nZiG " + major(wallets.balance(c.getCustomerRef(), "ZWG")));
    }

    private String statement(CustomerProfile c, List<String> in) {
        if (in.size() == 1) return con("Enter PIN");
        wallets.verifyPin("CUSTOMER", c.getCustomerRef(), in.get(1));
        StringBuilder sb = new StringBuilder("Last transactions");
        for (WalletTransfer t : wallets.history(c.getCustomerRef(), 5)) {
            boolean out = c.getCustomerRef().equals(t.getFromRef());
            sb.append("\n").append(t.getCreatedAt().toLocalDate()).append(' ').append(out ? "-" : "+")
                    .append(NotificationService.money(t.getAmountMinor(), t.getCurrency())).append(' ').append(t.getType().replace('_', ' '));
        }
        return end(sb.toString());
    }

    private String convert(String sid, String phone, CustomerProfile c, List<String> in) {
        if (in.size() == 1) return con("Convert\n1. USD to ZiG\n2. ZiG to USD");
        String from = "1".equals(in.get(1)) ? "USD" : "ZWG";
        String to = "USD".equals(from) ? "ZWG" : "USD";
        if (in.size() == 2) return con("Enter amount in " + label(from));
        long amount = amount(in.get(2));
        if (in.size() == 3) {
            FxRateService.Quote q = fx.quote(from, to, amount);
            return con("You get " + NotificationService.money(q.toAmountMinor(), to) + " at " + q.customerRate().setScale(4, java.math.RoundingMode.HALF_UP)
                    + "\nEnter PIN to confirm");
        }
        return once(sid, "fx", () -> {
            FxConversion x = fxConvert.convert(phone, from, to, amount, in.get(3), "USSD");
            return end("Converted. You received " + NotificationService.money(x.getToAmountMinor(), to) + ".");
        });
    }

    private String changePin(CustomerProfile c, List<String> in) {
        if (in.size() == 1) return con("Enter current PIN");
        if (in.size() == 2) return con("Enter new PIN");
        if (in.size() == 3) return con("Confirm new PIN");
        if (!in.get(2).equals(in.get(3))) return end("New PINs did not match.");
        wallets.changePin(c.getCustomerRef(), in.get(1), in.get(2));
        return end("PIN changed.");
    }

    // ── Agent ────────────────────────────────────────────────────────────

    private String agentMenu(String sid, Agent a, List<String> in) {
        if (in.isEmpty()) {
            return con("PayPort Agent " + a.getAgentCode() + "\n1. Cash in\n2. Float balance"
                    + ("SUPER_AGENT".equals(a.getAgentType()) ? "\n3. Transfer float" : "") + "\n9. My wallet");
        }
        switch (in.get(0)) {
            case "1" -> {
                if (in.size() == 1) return currencyMenu();
                String ccy = ccy(in.get(1));
                if (in.size() == 2) return con("Customer's mobile number");
                if (in.size() == 3) return con("Amount received (" + label(ccy) + ")");
                long amount = amount(in.get(3));
                if (in.size() == 4) {
                    CustomerProfile c = kyc.requireActive(in.get(2));
                    return con("Cash in " + NotificationService.money(amount, ccy) + " for " + displayName(c) + "?\nEnter agent PIN");
                }
                return once(sid, "cashin", () -> {
                    WalletService.TransferResult r = agentService.cashIn(a.getAgentCode(), in.get(4), in.get(2), ccy, amount, "USSD");
                    return end("Done. Ref " + shortRef(r.transferRef()) + ". Float left " + NotificationService.money(r.balanceAfterMinor(), ccy));
                });
            }
            case "2" -> {
                if (in.size() == 1) return con("Enter agent PIN");
                agentService.verifyAgentPin(a, in.get(1));
                var b = agentService.balances(a.getAgentRef());
                return end("Float USD " + major(b.get("floatUSD")) + "\nFloat ZiG " + major(b.get("floatZWG"))
                        + "\nCommission USD " + major(b.get("commissionUSD")));
            }
            case "3" -> {
                if (!"SUPER_AGENT".equals(a.getAgentType())) return end("Invalid choice.");
                if (in.size() == 1) return currencyMenu();
                String ccy = ccy(in.get(1));
                if (in.size() == 2) return con("Receiving agent code");
                if (in.size() == 3) return con("Amount (" + label(ccy) + ")");
                long amount = amount(in.get(3));
                if (in.size() == 4) return con("Transfer " + NotificationService.money(amount, ccy) + " float to " + agentService.activeAgent(in.get(2)).getBusinessName() + "?\nEnter agent PIN");
                return once(sid, "float", () -> end("Float sent. Ref " + shortRef(agentService.transferFloat(a.getAgentCode(), in.get(4), in.get(2), ccy, amount))));
            }
            default -> { return end("Invalid choice."); }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Final steps run once per USSD session: a re-delivered request gets the same answer, not a second payment. */
    private String once(String sid, String action, java.util.function.Supplier<String> work) {
        return idempotency.execute("ussd", sid + ":" + action, action, () -> {
            String answer;
            try {
                answer = work.get();
            } catch (PspException e) {
                answer = end(e.getMessage()); // a business refusal is the final answer for this session too
            }
            return new IdempotencyService.StoredResponse(200, answer, null);
        }).body();
    }

    private static String currencyMenu() { return con("Currency\n1. USD\n2. ZiG"); }
    private static String ccy(String choice) {
        return switch (choice) { case "1" -> "USD"; case "2" -> "ZWG"; default -> throw PspException.badRequest("BAD_CHOICE", "Invalid currency choice."); };
    }
    private static String label(String ccy) { return "ZWG".equals(ccy) ? "ZiG" : ccy; }

    static long amount(String s) {
        try {
            BigDecimal v = new BigDecimal(s.trim());
            if (v.signum() <= 0 || v.scale() > 2) throw new NumberFormatException();
            return v.movePointRight(2).longValueExact();
        } catch (Exception e) {
            throw PspException.badRequest("BAD_AMOUNT", "Invalid amount. Use numbers like 10 or 10.50.");
        }
    }

    private static long parseLong(String s, String msg) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { throw PspException.badRequest("BAD_INPUT", msg); }
    }

    private static String displayName(CustomerProfile c) {
        return c.getFirstName() + " " + (c.getLastName() == null || c.getLastName().isEmpty() ? "" : c.getLastName().charAt(0) + ".");
    }

    private static String major(Long minor) {
        long m = minor == null ? 0 : minor;
        return (m < 0 ? "-" : "") + Math.abs(m) / 100 + "." + String.format("%02d", Math.abs(m) % 100);
    }

    private static String shortRef(String ref) { return ref.length() > 10 ? ref.substring(ref.length() - 10) : ref; }
    static String con(String s) { return "CON " + s; }
    static String end(String s) { return "END " + s; }
}
