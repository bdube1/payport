package zw.co.payport.gateway.psp.common;

/**
 * Business error raised by any PSP module. Carries a stable machine code
 * (what API clients switch on) and the HTTP status it maps to.
 */
public class PspException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public PspException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static PspException badRequest(String code, String message) { return new PspException(code, message, 400); }
    public static PspException notFound(String what, Object id) { return new PspException("NOT_FOUND", what + " not found: " + id, 404); }
    public static PspException conflict(String code, String message) { return new PspException(code, message, 409); }
    public static PspException forbidden(String code, String message) { return new PspException(code, message, 403); }
    public static PspException unprocessable(String code, String message) { return new PspException(code, message, 422); }
    public static PspException unavailable(String code, String message) { return new PspException(code, message, 503); }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
}
