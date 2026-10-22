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

    /** Legacy settlement stops drafting once the Phase 3 settlement is LIVE (otherwise sessions could be paid twice). */
    @org.springframework.beans.factory.annotation.Value("${payport.settlement.mode:PARALLEL}")
    private String settlementMode;

    @Scheduled(cron = "0 0 6 * * *") // every day, 6am -- Zimbabwean merchants specifically asked for daily settlement, not weekly
    public void runDailySettlement() {
        if ("LIVE".equalsIgnoreCase(settlementMode)) {
            log.info("[SETTLEMENT_SCHEDULER] Legacy settlement retired (payport.settlement.mode=LIVE) -- not running");
            return;
        }
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
