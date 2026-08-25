package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.CheckoutSessionService;

/**
 * Sweeps CREATED/PENDING checkout sessions past their expiry into EXPIRED.
 * Same @Scheduled pattern as the existing InactiveAccountLockScheduler --
 * runs every 5 minutes, which is frequent enough that a merchant polling
 * a session's status never sees a stale CREATED/PENDING long after the
 * customer actually walked away.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutSessionExpiryScheduler {

    private final CheckoutSessionService checkoutSessionService;

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void sweep() {
        try {
            int count = checkoutSessionService.expireOverdueSessions();
            if (count > 0) {
                log.info("[CHECKOUT_EXPIRY] Swept {} overdue checkout session(s)", count);
            }
        } catch (Exception e) {
            log.error("[CHECKOUT_EXPIRY] Sweep failed: {}", e.getMessage(), e);
        }
    }
}
