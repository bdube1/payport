package zw.co.payport.gateway.psp.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.common.Refs;

import java.time.LocalDateTime;

/**
 * Publishes events onto the PSP event bus. Call it inside the same
 * transaction as the state change: the event exists if and only if the
 * change committed, which is what makes downstream processing reliable.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String publish(String eventType, String aggregateType, String aggregateId, Object payload) {
        String json;
        try {
            json = payload instanceof String s ? s : objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise " + eventType + " payload", e);
        }
        OutboxEvent ev = new OutboxEvent();
        ev.setEventId(Refs.newRef("evt"));
        ev.setEventType(eventType);
        ev.setAggregateType(aggregateType);
        ev.setAggregateId(aggregateId);
        ev.setPayload(json);
        ev.setStatus("NEW");
        ev.setAttempts(0);
        ev.setNextAttemptAt(LocalDateTime.now());
        repository.save(ev);
        return ev.getEventId();
    }
}
