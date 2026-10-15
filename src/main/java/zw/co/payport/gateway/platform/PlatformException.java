package zw.co.payport.gateway.platform;

/** A business rule refused the operation. code is stable (e.g. APPROVAL_SELF, APPROVAL_ROLE). */
public class PlatformException extends RuntimeException {
    private final String code;

    public PlatformException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
