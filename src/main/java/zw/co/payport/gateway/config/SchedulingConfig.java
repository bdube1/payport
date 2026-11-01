package zw.co.payport.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs (payment windows, fulfilment, reconciliation, settlement, screening, pushes...) run on one server
 * only. With several servers behind the load balancer, set PAYPORT_SCHEDULING_ENABLED=false on all but one (the
 * worker); the others only answer requests. One server alone runs everything (the default).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "payport.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
