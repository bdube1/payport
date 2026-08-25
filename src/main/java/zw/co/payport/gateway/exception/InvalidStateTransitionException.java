package zw.co.payport.gateway.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// ── Invalid state transition ──────────────────────────────────────────────────
@ResponseStatus(HttpStatus.CONFLICT)
public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(String reference, Object from, Object to) {
        super(String.format("Invalid transition for transaction %s: %s → %s is not permitted", reference, from, to));
    }
}
