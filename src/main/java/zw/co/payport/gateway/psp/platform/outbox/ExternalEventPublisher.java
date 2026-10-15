package zw.co.payport.gateway.psp.platform.outbox;

/**
 * Optional bridge to an external broker (Kafka, RabbitMQ, NATS) for the data
 * warehouse, SIEM and any module that is later split into its own service.
 * The default implementation does nothing; register a bean to replace it.
 */
public interface ExternalEventPublisher {

    void publish(PspEvent event) throws Exception;

    ExternalEventPublisher NO_OP = event -> { };
}
