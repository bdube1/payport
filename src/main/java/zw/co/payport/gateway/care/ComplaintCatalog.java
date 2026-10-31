package zw.co.payport.gateway.care;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The complaint types Payport handles (owner's list, 2026-10-30): merchant, agent and general wallet complaints, with
 * the likely cause and how each is resolved. Priority sets the deadline: HIGH 4 hours (fraud, money taken without
 * authority), NORMAL 24 hours (money missing or stuck), LOW 72 hours (service and information).
 */
public final class ComplaintCatalog {

    private ComplaintCatalog() {}

    public record Category(String code, String area, String title, String cause, String resolution, String priority, boolean money) {}

    public static final List<Category> ALL = List.of(
            c("M_DEBITED_NOT_CREDITED", "MERCHANT", "Customer debited but merchant not credited", "Network failure, timeout or incomplete processing",
                    "Trace the transaction by reference, confirm the merchant credit, then complete the payment or reverse the customer's debit.", "NORMAL", true),
            c("M_CHARGED_TWICE", "MERCHANT", "Customer charged twice for one purchase", "The payment was repeated after a delayed confirmation",
                    "Identify the duplicate transaction and reverse the additional debit.", "NORMAL", true),
            c("M_NOT_RECEIVED", "MERCHANT", "Merchant says payment was not received", "Delayed confirmation or merchant system not updated",
                    "Verify the transaction against provider records and confirm the payment status to the merchant.", "NORMAL", false),
            c("M_WRONG_AMOUNT", "MERCHANT", "Incorrect amount charged", "Cashier entered the wrong amount or the customer approved it",
                    "Verify the original payment and the merchant's sales record; refund or adjust.", "NORMAL", true),
            c("M_REFUND_NOT_RECEIVED", "MERCHANT", "Refunded by merchant but money not received", "Refund not initiated, delayed or processed wrongly",
                    "Trace the refund reference and its status; process or correct the refund.", "NORMAL", true),
            c("M_SETTLEMENT_NOT_RECEIVED", "MERCHANT", "Merchant paid but bank account not credited", "Settlement delay, rejected bank instruction or reconciliation problem",
                    "Investigate the settlement batch, reconcile the merchant's transactions and reprocess the settlement where necessary.", "NORMAL", false),
            c("M_WRONG_MERCHANT", "MERCHANT", "Payment made to the wrong merchant", "Wrong merchant number or merchant chosen",
                    "Investigate and reverse where eligible and the funds are recoverable.", "NORMAL", true),
            c("M_NOT_DELIVERED", "MERCHANT", "Paid but goods or services not delivered", "Merchant operational failure or misconduct",
                    "Investigate the merchant and the evidence; facilitate a refund or escalate the dispute.", "NORMAL", true),
            c("M_DECLINED_WITH_BALANCE", "MERCHANT", "Payment declined despite enough balance", "System outage, transaction limits or processing error",
                    "Check the balance, limits and system logs; fix the technical issue or advise the customer.", "LOW", false),
            c("S_SELLER_CONDUCT", "MERCHANT", "Payport Store: customer reported a seller", "Rude or abusive seller, a suspected scam, asking to be paid outside Payport or misleading items",
                    "Read the report and the chat, contact the seller, and warn, suspend the store or refund the customer as the evidence shows.", "NORMAL", false),
            c("S_CUSTOMER_CONDUCT", "MERCHANT", "Payport Store: seller reported a customer", "Abusive messages, spam or a suspected scam by a customer",
                    "Read the report and the chat; warn or block the customer and support the seller.", "LOW", false),

            c("A_CASH_OUT_NO_CASH", "AGENT", "Wallet debited but agent gave no cash", "Agent not credited or confirmation delayed",
                    "Verify the transaction and the agent's float; tell the agent to release the cash if credited, or reverse the customer's debit.", "NORMAL", true),
            c("A_CASH_IN_NOT_CREDITED", "AGENT", "Gave the agent cash but wallet not credited", "Agent did not complete the cash-in",
                    "Verify the agent's records; credit the customer's wallet or have the cash refunded after investigation.", "NORMAL", true),
            c("A_NO_CASH", "AGENT", "Agent has no cash for withdrawal", "Not enough physical cash",
                    "Direct the customer to another agent and arrange cash rebalancing for the agent.", "LOW", false),
            c("A_NO_FLOAT", "AGENT", "Agent has no float for deposits", "Not enough electronic float",
                    "Direct the customer to another agent and arrange a float top-up for the agent.", "LOW", false),
            c("A_EXTRA_FEES", "AGENT", "Agent charged extra or unauthorised fees", "Agent misconduct or not following approved tariffs",
                    "Investigate, refund unauthorised charges where appropriate and take action against the agent.", "NORMAL", true),
            c("A_REFUSED", "AGENT", "Agent refused to serve", "Liquidity shortage, limits or agent misconduct",
                    "Establish the reason, direct the customer to another agent and investigate repeated complaints.", "LOW", false),
            c("A_WRONG_NUMBER", "AGENT", "Money sent to the wrong number", "Wrong recipient details entered",
                    "Trace the funds and reverse where eligible and recoverable.", "NORMAL", true),
            c("A_NO_CONFIRMATION", "AGENT", "Transaction done but no confirmation", "SMS failure or network interruption",
                    "Verify the transaction by reference and resend the confirmation.", "LOW", false),
            c("A_UNAUTHORISED", "AGENT", "Agent withdrew money without authorisation", "Fraud or compromised customer credentials",
                    "Secure the wallet, investigate the logs, attempt recovery and escalate suspected fraud.", "HIGH", true),
            c("A_UNAVAILABLE", "AGENT", "Agent registered but unavailable", "Agent not operating or poor coverage",
                    "Suggest other agents and monitor the agent's activity.", "LOW", false),
            c("A_CASH_OUT_PENDING", "AGENT", "Cash-out still pending", "Network timeout or incomplete processing",
                    "Trace the status and either complete the withdrawal or reverse the debit.", "NORMAL", true),
            c("A_STATUS_MISMATCH", "AGENT", "Agent says failed but customer was debited", "Different status in the provider and agent systems",
                    "Reconcile both sides and establish the final status before releasing cash or reversing.", "NORMAL", true),

            c("G_FAILED_NOT_REVERSED", "GENERAL", "Failed transaction not reversed", "Reversal failed or the outcome is unresolved",
                    "Investigate and reverse where appropriate.", "NORMAL", true),
            c("G_NO_ACCESS", "GENERAL", "Cannot access wallet", "Wrong PIN, account restriction or technical problem",
                    "Verify identity, reset the PIN or lift the restriction.", "NORMAL", false),
            c("G_UNAUTHORISED", "GENERAL", "Unauthorised wallet transaction", "Fraud, stolen credentials or SIM swap",
                    "Freeze the wallet, investigate the transaction and attempt recovery.", "HIGH", true),
            c("G_CHARGES_UNCLEAR", "GENERAL", "Charges are unclear", "Tariffs not clear to the customer",
                    "Explain the charges that applied.", "LOW", false),
            c("G_SUPPORT_UNREACHABLE", "GENERAL", "Cannot reach customer support", "Overloaded support channels",
                    "Give the other support channels and take the complaint now.", "LOW", false),
            c("G_FOLLOW_UP", "GENERAL", "Follow-up on an unresolved complaint", "Complaint not tracked or not updated",
                    "Link the earlier complaint, assign an owner and update the customer until it is closed.", "NORMAL", false),
            c("G_OTHER", "GENERAL", "Other", "", "Investigate and resolve.", "LOW", false));

    private static Category c(String code, String area, String title, String cause, String resolution, String priority, boolean money) {
        return new Category(code, area, title, cause, resolution, priority, money);
    }

    private static final Map<String, Category> BY_CODE = ALL.stream().collect(Collectors.toMap(Category::code, Function.identity()));

    public static Optional<Category> find(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code.trim().toUpperCase()));
    }

    public static int hours(String priority) {
        return switch (priority) { case "HIGH" -> 4; case "NORMAL" -> 24; default -> 72; };
    }
}
