package zw.co.payport.gateway.wallets;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.channels.ChannelUnavailableException;
import zw.co.payport.gateway.ledger.LedgerException;
import zw.co.payport.gateway.service.adaptor.FulfilmentProviderAdaptor;

import java.util.List;
import java.util.Map;

/**
 * Delivers the WALLET_TOPUP product: once the customer's payment is confirmed, credits the wallet
 * (Dr PROVIDER_RECEIVABLE[rail that collected] · Cr CUSTOMER_WALLET). Idempotent per payment
 * reference, so a re-delivery never credits twice. A refusal (wallet frozen, limit) is final and the
 * payment goes to refund like any failed delivery.
 */
@Slf4j
@Component
public class WalletTopUpAdaptor implements FulfilmentProviderAdaptor {

    public static final String CODE = "PAYPORT_WALLET";

    private final WalletService wallets;
    private final JdbcTemplate jdbc;

    public WalletTopUpAdaptor(WalletService wallets, JdbcTemplate jdbc) {
        this.wallets = wallets;
        this.jdbc = jdbc;
    }

    @Override
    public String getProviderCode() {
        return CODE;
    }

    @Override
    public FulfilmentResult deliver(FulfilmentRequest r) {
        List<String> rail = jdbc.queryForList("SELECT payment_provider_code FROM transactions WHERE reference = ?", String.class, r.getPayportReference());
        if (rail.isEmpty() || rail.get(0) == null) {
            return FulfilmentResult.builder().success(false).errorCode("NO_RAIL").message("The payment rail of " + r.getPayportReference() + " is not recorded").build();
        }
        try {
            String entry = wallets.creditTopUp(r.getPayportReference(), r.getRecipientIdentifier(), r.getCurrency(), r.getAmountCents(), rail.get(0), "SYSTEM:wallet-topup");
            return FulfilmentResult.builder().success(true).fulfilmentReference(entry)
                    .fulfilmentData("{\"ledgerEntry\":\"" + entry + "\"}")
                    .message("Wallet credited").customerMessage("Your PayPort wallet has been topped up.").rawResponse(entry).build();
        } catch (IllegalArgumentException | IllegalStateException | ChannelUnavailableException e) {
            return FulfilmentResult.builder().success(false).errorCode("WALLET_REFUSED").message(e.getMessage())
                    .customerMessage("Your wallet could not be topped up: " + e.getMessage() + " You will be refunded.").rawResponse(e.getMessage()).build();
        } catch (LedgerException e) {
            log.error("[WALLET] top-up {} refused by the ledger: {}", r.getPayportReference(), e.getMessage());
            return FulfilmentResult.builder().success(false).outcomeUnknown(true).message(e.getMessage()).rawResponse(e.getMessage()).build();
        }
    }

    @Override
    public FulfilmentStatusResult queryDelivery(String reference) {
        List<Map<String, Object>> op = jdbc.queryForList("SELECT entry_ref FROM wallet_operations WHERE kind = 'TOPUP' AND (reference = ? OR entry_ref = ?) LIMIT 1", reference, reference);
        if (op.isEmpty()) return FulfilmentStatusResult.builder().status("NOT_FOUND").message("No wallet credit for " + reference).build();
        return FulfilmentStatusResult.builder().status("DELIVERED").fulfilmentReference((String) op.get(0).get("entry_ref")).build();
    }
}
