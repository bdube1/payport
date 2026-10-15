package zw.co.payport.gateway.psp.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.psp.fx.FxRateService;
import zw.co.payport.gateway.psp.wallet.WalletTransfer;
import zw.co.payport.gateway.psp.wallet.WalletTransferRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Post-event AML transaction monitoring over the previous day's wallet and
 * agent activity. Raises alerts for compliance; it never blocks money itself.
 *
 *   CTR_THRESHOLD     a customer's cash-in + cash-out in a day reached the cash reporting threshold
 *   STRUCTURING       several transactions just under that threshold within the day
 *   RAPID_MOVEMENT    money received and >= 90% sent on within the same day (pass-through / mule)
 *   MANY_SENDERS      one wallet received from many distinct senders in a day (collection mule)
 *
 * Thresholds are configuration: set the cash threshold to the figure in the
 * FIU's current directive (in USD minor units; ZWG is converted at the mid rate).
 */
@Service
public class TransactionMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(TransactionMonitoringService.class);
    private static final Set<String> CASH = Set.of("CASH_IN", "CASH_OUT");
    private static final Set<String> CREDIT_TYPES = Set.of("P2P", "CASH_IN");
    private static final Set<String> DEBIT_TYPES = Set.of("P2P", "CASH_OUT", "WITHDRAWAL", "WALLET_MERCHANT_PAYMENT");

    private final WalletTransferRepository transfers;
    private final AmlScreeningService aml;
    private final FxRateService fx;

    @Value("${payport.psp.aml.cash-threshold-usd-minor:1000000}")
    private long cashThreshold = 1_000_000;
    @Value("${payport.psp.aml.structuring-count:3}")
    private int structuringCount = 3;
    @Value("${payport.psp.aml.rapid-movement-min-usd-minor:50000}")
    private long rapidMin = 50_000;
    @Value("${payport.psp.aml.many-senders:10}")
    private int manySenders = 10;

    public TransactionMonitoringService(WalletTransferRepository transfers, AmlScreeningService aml, FxRateService fx) {
        this.transfers = transfers;
        this.aml = aml;
        this.fx = fx;
    }

    @Scheduled(cron = "${payport.psp.aml.monitoring-cron:0 30 3 * * *}", zone = "Africa/Harare")
    public void runForYesterday() {
        run(LocalDate.now().minusDays(1));
    }

    public int run(LocalDate day) {
        List<WalletTransfer> all = transfers.findByCreatedAtBetweenAndStatus(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), "COMPLETED");
        Map<String, long[]> cash = new HashMap<>();            // customer -> [cashUsd]
        Map<String, Integer> nearThreshold = new HashMap<>();
        Map<String, long[]> flows = new HashMap<>();           // customer -> [creditsUsd, debitsUsd]
        Map<String, Set<String>> senders = new HashMap<>();
        for (WalletTransfer t : all) {
            long usd = usd(t);
            String cust = customerSide(t);
            if (CASH.contains(t.getType()) && cust != null) {
                cash.computeIfAbsent(cust, k -> new long[1])[0] += usd;
                if (usd >= cashThreshold * 8 / 10 && usd < cashThreshold) nearThreshold.merge(cust, 1, Integer::sum);
            }
            if (CREDIT_TYPES.contains(t.getType()) && t.getToRef() != null && !t.getToRef().startsWith("agent:")) {
                flows.computeIfAbsent(t.getToRef(), k -> new long[2])[0] += usd;
                if ("P2P".equals(t.getType()) && t.getFromRef() != null) senders.computeIfAbsent(t.getToRef(), k -> new HashSet<>()).add(t.getFromRef());
            }
            if (DEBIT_TYPES.contains(t.getType()) && t.getFromRef() != null && !t.getFromRef().startsWith("agent:")) {
                flows.computeIfAbsent(t.getFromRef(), k -> new long[2])[1] += usd + 0;
            }
        }
        int raised = 0;
        String key = day.toString();
        for (var e : cash.entrySet()) {
            if (e.getValue()[0] >= cashThreshold) {
                aml.raiseAlert("CTR_THRESHOLD", "CUSTOMER", e.getKey(), null, key, null, 100, e.getValue()[0],
                        "Cash activity " + e.getValue()[0] + " USD minor on " + day + " >= threshold " + cashThreshold);
                raised++;
            }
        }
        for (var e : nearThreshold.entrySet()) {
            if (e.getValue() >= structuringCount) {
                aml.raiseAlert("STRUCTURING", "CUSTOMER", e.getKey(), null, key, null, 80, null,
                        e.getValue() + " cash transactions between 80% and 100% of the reporting threshold on " + day);
                raised++;
            }
        }
        for (var e : flows.entrySet()) {
            long in = e.getValue()[0], out = e.getValue()[1];
            if (in >= rapidMin && out * 10 >= in * 9) {
                aml.raiseAlert("RAPID_MOVEMENT", "CUSTOMER", e.getKey(), null, key, null, 60, in,
                        "Received " + in + " and sent on " + out + " (USD minor) on " + day);
                raised++;
            }
        }
        for (var e : senders.entrySet()) {
            if (e.getValue().size() >= manySenders) {
                aml.raiseAlert("MANY_SENDERS", "CUSTOMER", e.getKey(), null, key, null, 60, null,
                        e.getValue().size() + " distinct senders on " + day);
                raised++;
            }
        }
        log.info("[AML-TM] {}: {} transfers reviewed, {} alerts", day, all.size(), raised);
        return raised;
    }

    private long usd(WalletTransfer t) {
        try {
            return fx.usdEquivalent(t.getAmountMinor(), t.getCurrency());
        } catch (Exception e) {
            return t.getAmountMinor();
        }
    }

    private static String customerSide(WalletTransfer t) {
        if ("CASH_IN".equals(t.getType())) return t.getToRef();
        if ("CASH_OUT".equals(t.getType())) return t.getFromRef();
        return null;
    }
}
