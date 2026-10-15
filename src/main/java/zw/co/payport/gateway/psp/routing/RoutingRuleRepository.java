package zw.co.payport.gateway.psp.routing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, Long> {
    List<RoutingRule> findByInstrumentAndCurrencyAndEnabledTrue(String instrument, String currency);
    List<RoutingRule> findAllByOrderByInstrumentAscCurrencyAscPriorityAsc();
}
