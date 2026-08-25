package zw.co.payport.gateway.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateReferenceException extends RuntimeException {
    public DuplicateReferenceException(String reference) {
        super("Transaction reference already exists: " + reference);
    }
}
