package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.service.WebhookService;

/** Same @Scheduled pattern as CheckoutSessionExpiryScheduler -- picks up any PENDING delivery whose next_attempt_at has passed. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookRetryScheduler {

    private final WebhookService webhookService;

    @Scheduled(fixedRate = 60 * 1000)
    public void sweep() {
        try {
            int count = webhookService.retryDueDeliveries();
            if (count > 0) {
                log.info("[WEBHOOK_RETRY] Retried {} due delivery(ies)", count);
            }
        } catch (Exception e) {
            log.error("[WEBHOOK_RETRY] Sweep failed: {}", e.getMessage(), e);
        }
    }
}
