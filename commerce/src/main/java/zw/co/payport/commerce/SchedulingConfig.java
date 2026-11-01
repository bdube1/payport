package zw.co.payport.commerce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs (payment events, reminders, clean-ups) on one server only: COMMERCE_SCHEDULING_ENABLED=false elsewhere. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "commerce.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
