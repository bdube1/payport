package zw.co.payport.gateway.psp.platform.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import zw.co.payport.gateway.psp.common.Actor;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the tamper-evident audit trail. Every privileged action in the PSP
 * modules (approvals, suspensions, payouts, adjustments, KYC decisions,
 * key rotation...) records who, what, on which object, from where.
 *
 * Runs inside the caller's transaction: if the action rolls back, so does its audit row.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AuditEvent record(Actor actor, String action, String objectType, String objectRef, Map<String, ?> details) {
        AuditEvent e = new AuditEvent();
        e.setEventRef(Refs.newRef("aud"));
        e.setActor(actor.id());
        e.setActorRoles(actor.rolesCsv());
        e.setAction(action);
        e.setObjectType(objectType);
        e.setObjectRef(objectRef);
        e.setDetails(toJson(details));
        HttpServletRequest req = currentRequest();
        if (req != null) {
            String fwd = req.getHeader("X-Forwarded-For");
            e.setIpAddress(fwd != null ? fwd.split(",")[0].trim() : req.getRemoteAddr());
            e.setRequestId(req.getHeader("X-Request-ID"));
        }
        e.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        e.setContentHash(hash(e));
        return repository.save(e);
    }

    public List<AuditEvent> forObject(String objectType, String objectRef) {
        return repository.findByObjectTypeAndObjectRefOrderByIdAsc(objectType, objectRef);
    }

    /** Canonical hash of an audit row; shared with the verifier. */
    public static String hash(AuditEvent e) {
        return ChainHasher.contentHash(e.getEventRef(), e.getActor(), e.getActorRoles(), e.getAction(),
                e.getObjectType(), e.getObjectRef(), e.getDetails(), e.getIpAddress(), e.getRequestId(),
                e.getCreatedAt() != null ? e.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).toString() : null);
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(details));
        } catch (Exception ex) {
            return String.valueOf(details);
        }
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }
}
