package zw.co.payport.gateway.psp.platform.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class AuditChainSource implements ChainSource {

    private final AuditEventRepository repository;

    public AuditChainSource(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public String chainName() {
        return "AUDIT";
    }

    @Override
    public List<Row> sealable(long afterId, LocalDateTime cutoff, int limit) {
        return repository.findSealable(afterId, cutoff, PageRequest.of(0, limit)).stream()
                .map(e -> new Row(e.getId(), e.getContentHash(), null)).toList();
    }

    @Override
    public List<Row> range(long fromId, long toId) {
        return repository.findRange(fromId, toId).stream()
                .map(e -> new Row(e.getId(), e.getContentHash(), AuditService.hash(e))).toList();
    }
}
