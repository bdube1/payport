package zw.co.payport.gateway.psp.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.PspException;

/**
 * Prepares (never approves) the daily settlement batch per currency, and
 * polls API payout rails. A human must still submit and a different human
 * approve every batch.
 */
@Component
public class SettlementEngineScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementEngineScheduler.class);

    private final SettlementEngine settlements;
    private final Environment env;

    @Value("${payport.psp.settlement.auto-draft:true}")
    private boolean autoDraft = true;

    public SettlementEngineScheduler(SettlementEngine settlements, Environment env) {
        this.settlements = settlements;
        this.env = env;
    }

    @Scheduled(cron = "${payport.psp.settlement.draft-cron:0 0 6 * * MON-FRI}", zone = "Africa/Harare")
    public void draftDailyBatches() {
        if (!autoDraft) return;
        for (String ccy : new String[]{"USD", "ZWG"}) {
            String cashAccount = env.getProperty("payport.psp.settlement.cash-account." + ccy);
            if (cashAccount == null || cashAccount.isBlank()) continue;
            try {
                SettlementEngine.BatchResult r = settlements.createBatch(ccy, cashAccount, null, Actor.system("settlement-draft"));
                log.info("[SETTLEMENT] Drafted {} batch {} with {} instructions; awaiting submit + approval",
                        ccy, r.batch().getBatchRef(), r.instructions().size());
            } catch (PspException e) {
                log.info("[SETTLEMENT] No {} draft created: {}", ccy, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 120_000)
    public void pollRails() {
        int n = settlements.pollRails();
        if (n > 0) log.info("[SETTLEMENT] {} payout(s) resolved from rail status", n);
    }
}
