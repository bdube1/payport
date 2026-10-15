package zw.co.payport.gateway.psp.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TerminalRepository extends JpaRepository<Terminal, Long> {
    Optional<Terminal> findByTerminalId(String terminalId);
    List<Terminal> findByMerchantId(Long merchantId);
}
