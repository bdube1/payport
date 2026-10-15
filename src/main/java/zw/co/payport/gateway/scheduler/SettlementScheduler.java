package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.entity.Settlement;
import zw.co.payport.gateway.service.SettlementService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

    private final SettlementService settlementService;

    @org.springframework.beans.factory.annotation.Value("${payport.legacy.settlement.enabled:false}")
    private boolean legacyEnabled;

    @Scheduled(cron = "0 0 6 * * *") // every day, 6am -- Zimbabwean merchants specifically asked for daily settlement, not weekly
    public void runDailySettlement() {
        if (!legacyEnabled) return; // PSP settlement engine drafts batches instead (psp.settlement.SettlementEngineScheduler)
        try {
            LocalDateTime periodEnd = LocalDateTime.now();
            LocalDateTime periodStart = periodEnd.minusDays(1);
            List<Settlement> created = settlementService.runForAllMerchants(periodStart, periodEnd);
            log.info("[SETTLEMENT_SCHEDULER] Daily run complete: {} settlement(s) created", created.size());
        } catch (Exception e) {
            log.error("[SETTLEMENT_SCHEDULER] Daily run failed: {}", e.getMessage(), e);
        }
    }
}
