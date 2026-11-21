package com.meridian.payments.intake.port;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.meridian.payments.intake.config.Json;
import com.meridian.payments.intake.event.CanonicalEvent;

/**
 * A canonical event as a Service Bus message (ADR-0003):
 * MessageId = event ID (duplicate detection), SessionId = payment key (one payment at a time),
 * and correlation ID, country and schema version as application properties.
 */
public record OutgoingMessage(String messageId, String sessionId, Map<String, String> properties, byte[] body) {

    public static OutgoingMessage from(CanonicalEvent event) {
        try {
            return new OutgoingMessage(
                    event.eventId(),
                    event.paymentKey(),
                    Map.of(
                            "correlationId", event.correlationId(),
                            "country", event.country(),
                            "schemaVersion", event.schemaVersion()),
                    Json.mapper().writeValueAsBytes(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Canonical event could not be serialized", e);
        }
    }
}
