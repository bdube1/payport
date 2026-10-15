package zw.co.payport.gateway.psp.platform.outbox;

/**
 * Subscriber on the PSP event bus. Delivery is at-least-once, so handlers
 * must be idempotent (use the eventId or a business key to de-duplicate).
 * Each handler runs in its own database transaction together with the
 * record that marks it delivered.
 */
public interface PspEventHandler {

    /** Stable name; used to record which handlers have processed which events. Do not rename casually. */
    String name();

    boolean supports(String eventType);

    void handle(PspEvent event) throws Exception;
}
