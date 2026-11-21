package com.meridian.payments.intake.provider;

import java.time.Instant;

import org.springframework.http.HttpHeaders;

/**
 * Contract of a provider adapter (NFR9). All provider-specific logic lives behind it, so adding
 * a provider means adding an adapter, its configuration and its tests; the shared flow does not
 * change.
 */
public interface ProviderAdapter {

    Provider provider();

    /** Country of the provider, from its configuration. */
    String country();

    /**
     * Verifies the provider's signature over the raw request bytes, before the body is parsed (FR1).
     *
     * @throws InvalidSignatureException if the signature or its timestamp is not valid
     */
    void verifySignature(HttpHeaders headers, byte[] rawBody);

    /** Translates an authentic notification into the canonical event, or parks it (FR2). */
    MappingResult map(byte[] rawBody, NotificationContext context);

    /** Data the intake knows about the request, independent of the provider. */
    record NotificationContext(String correlationId, Instant receivedAt) {
    }

    /** Signature or timestamp not valid. The message is a short reason, never secret material. */
    class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String reason) {
            super(reason);
        }
    }
}
