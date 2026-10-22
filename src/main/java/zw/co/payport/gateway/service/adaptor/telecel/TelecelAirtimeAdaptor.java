package zw.co.payport.gateway.service.adaptor.telecel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;

/**
 * TelecelAirtimeAdaptor
 *
 * Delivers airtime to Telecel numbers via the EstelTprServices SOAP API.
 * Supports: getBalance, getTopup, getTransHistory, wallettransfer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelecelAirtimeAdaptor implements FulfilmentProviderAdaptor {

    private final TelecelSoapClient soapClient;

    // Existing single credential pair -- this is the USD agent account,
    // kept under its original property name so nothing already configured
    // in production breaks.
    @Value("${payport.telecel.agent-code}")
    private String agentCode;

    @Value("${payport.telecel.mpin}")
    private String mpin;

    // ZWG is a separate agent account, per Telecel -- optional and blank by
    // default until those credentials are actually issued. Balance checks
    // stay honestly "not configured" rather than reusing the USD account
    // and mislabeling its balance as ZWG.
    @Value("${payport.telecel.zwg-agent-code:}")
    private String zwgAgentCode;

    @Value("${payport.telecel.zwg-mpin:}")
    private String zwgMpin;

    @Override
    public String getProviderCode() {
        return "TELECEL";
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest request) throws Exception {
        log.info("[TELECEL] Delivering airtime | ref={} recipient={} amountCents={}",
            request.getPayportReference(),
            maskPhone(request.getRecipientIdentifier()),
            request.getAmountCents());

        String destination = normalisePhone(request.getRecipientIdentifier());

        String agentTransId = request.getPayportReference()
            .replaceAll("[^A-Za-z0-9]", "")
            .substring(0, Math.min(request.getPayportReference().replaceAll("[^A-Za-z0-9]", "").length(), 20));

        TelecelSoapClient.TelecelTopupResult result = soapClient.getTopup(
            agentCode, mpin, destination, request.getAmountCents(), agentTransId);

        // Code 106 ("Duplicate Transaction - Reference already used")
        // doesn't mean this attempt failed -- it means Telecel has
        // already seen this exact reference before, exactly what a
        // retry triggers if the original attempt actually reached them.
        // Same situation, same fix as Econet and NetOne: resolve it via
        // their own query capability (getTransHistory, exposed here as
        // queryTransaction) rather than recording a fresh failure on
        // something that may have already succeeded.
        if (!result.isSuccess() && "106".equals(result.getResultCode())) {
            log.info("[TELECEL] Duplicate response for {} -- querying actual status before deciding the outcome", request.getPayportReference());
            try {
                TelecelSoapClient.TelecelTopupResult queryResult = soapClient.queryTransaction(agentCode, mpin, agentTransId);
                log.info("[TELECEL] Duplicate resolution for {} -- query resultCode={}, success={}",
                        request.getPayportReference(), queryResult.getResultCode(), queryResult.isSuccess());

                if (queryResult.isSuccess()) {
                    return FulfilmentResult.builder()
                            .success(true)
                            .fulfilmentReference(queryResult.getProviderTransId() != null ? queryResult.getProviderTransId() : request.getPayportReference())
                            .message(queryResult.getResultDescription())
                            .rawResponse(result.getRawResponse() + " | Resolved via queryTransaction: " + queryResult.getRawResponse())
                            .build();
                }
                // Query itself confirms it genuinely didn't go through --
                // use its own result as the more authoritative, current
                // answer for the failure path below.
                result = queryResult;
            } catch (Exception e) {
                // Couldn't resolve it either way -- don't guess. Falls
                // through with the original duplicate message, which
                // already tells ops this needs a manual check.
                log.error("[TELECEL] Could not resolve duplicate status for {}: {}", request.getPayportReference(), e.getMessage(), e);
            }
        }

        if (result.isSuccess()) {
            log.info("[TELECEL] ✅ Airtime delivered | ref={} providerRef={}",
                request.getPayportReference(), result.getProviderTransId());

            return FulfilmentResult.builder()
                .success(true)
                .fulfilmentReference(result.getProviderTransId())
                .message(result.getResultDescription())
                .rawResponse(result.getRawResponse())
                .build();
        } else {
            log.warn("[TELECEL] ❌ Airtime delivery failed | ref={} reason={}",
                request.getPayportReference(), result.getResultDescription());

            return FulfilmentResult.builder()
                .success(false)
                // "ERROR" = the SOAP call itself failed (TelecelSoapClient catch): the top-up may have gone through.
                .outcomeUnknown("ERROR".equals(result.getResultCode()))
                .fulfilmentReference(request.getPayportReference())
                .message(result.getResultDescription())
                .rawResponse(result.getRawResponse())
                .build();
        }
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String fulfilmentReference) throws Exception {
        log.info("[TELECEL] Querying transaction: {}", fulfilmentReference);

        String agentTransId = fulfilmentReference
            .replaceAll("[^A-Za-z0-9]", "")
            .substring(0, Math.min(fulfilmentReference.replaceAll("[^A-Za-z0-9]", "").length(), 20));

        TelecelSoapClient.TelecelTopupResult result = soapClient.queryTransaction(
            agentCode, mpin, agentTransId);

        // "ERROR" means the query call itself failed: that says nothing about the top-up.
        String status = result.isSuccess() ? "DELIVERED" : "ERROR".equals(result.getResultCode()) ? "UNKNOWN" : "FAILED";

        return FulfilmentStatusResult.builder()
            .status(status)
            .fulfilmentReference(result.getProviderTransId() != null
                ? result.getProviderTransId() : fulfilmentReference)
            .message(result.getResultDescription())
            .build();
    }

    // ─── Additional Telecel Methods ───────────────────────────────────────────

    public TelecelSoapClient.TelecelBalanceResult getBalance() {
        log.info("[TELECEL] Checking wallet balance");
        return soapClient.getBalance(agentCode, mpin);
    }

    /**
     * Currency-aware balance check -- Telecel operates USD and ZWG as
     * separate agent accounts (confirmed), not a single account with a
     * currency parameter. Returns null if the requested currency's
     * credentials aren't configured yet, rather than falling back to the
     * USD account and mislabeling its figure.
     */
    public TelecelSoapClient.TelecelBalanceResult getBalance(String currency) {
        boolean isZwg = "ZWG".equalsIgnoreCase(currency);
        String code = isZwg ? zwgAgentCode : agentCode;
        String pin = isZwg ? zwgMpin : mpin;
        if (code == null || code.isBlank() || pin == null || pin.isBlank()) {
            return null;
        }
        log.info("[TELECEL] Checking {} wallet balance", currency);
        return soapClient.getBalance(code, pin);
    }

    public TelecelSoapClient.TelecelWalletTransferResult transferToWallet(String destinationAgentCode,
                                                                          long amountCents,
                                                                          String reference,
                                                                          String comments) {
        double amountDollars = amountCents / 100.0;
        
        String agentTransId = reference
            .replaceAll("[^A-Za-z0-9]", "")
            .substring(0, Math.min(reference.replaceAll("[^A-Za-z0-9]", "").length(), 20));

        log.info("[TELECEL] Initiating wallet transfer | ref={} destination={} amount=${}",
            reference, destinationAgentCode, amountDollars);

        return soapClient.walletTransfer(
            agentCode, mpin, destinationAgentCode, amountDollars, agentTransId, comments);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String normalisePhone(String phone) {
        if (phone == null) return "";
        if (phone.startsWith("+263")) return phone.substring(1);
        if (phone.startsWith("0"))    return "263" + phone.substring(1);
        if (phone.startsWith("263"))  return phone;
        return phone;
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "****";
        return phone.substring(0, 4) + "****" + phone.substring(phone.length() - 3);
    }
}
