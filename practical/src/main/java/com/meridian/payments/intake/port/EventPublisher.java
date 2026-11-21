package com.meridian.payments.intake.port;

/**
 * Publishes canonical events to the topic {@code premium-payments}. Production: Azure Service Bus
 * with a managed identity. Here: {@code stub.InMemoryEventPublisher}.
 */
public interface EventPublisher {

    /**
     * Returns only once the broker has stored the message durably.
     *
     * @throws PublishException if the broker is unavailable
     */
    void publish(OutgoingMessage message);

    class PublishException extends RuntimeException {
        public PublishException(String message) {
            super(message);
        }
    }
}
