package zw.co.payport.gateway.psp.platform.outbox;

/** What a handler receives. {@code payloadJson} is the JSON written by the publisher. */
public record PspEvent(String eventId, String eventType, String aggregateType, String aggregateId, String payloadJson) {}
