package zw.co.payport.gateway.psp.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    Optional<Agent> findByAgentRef(String agentRef);
    Optional<Agent> findByAgentCode(String agentCode);
    Optional<Agent> findByMsisdn(String msisdn);
    List<Agent> findByParentAgentId(Long parentAgentId);
    List<Agent> findByStatus(String status);
}
