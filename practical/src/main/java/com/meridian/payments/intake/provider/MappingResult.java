package com.meridian.payments.intake.provider;

import com.meridian.payments.intake.event.CanonicalEvent;

/** Outcome of translating an authentic notification. */
public sealed interface MappingResult {

    /**
     * Translated and valid against the event contract.
     *
     * @param providerStatus the status in the provider's own words, kept for Operations (FR7)
     */
    record Mapped(CanonicalEvent event, String providerStatus) implements MappingResult {
    }

    /**
     * Authentic but cannot be read or translated: it is kept for Operations and never guessed.
     *
     * @param providerEventId       null if it could not be read
     * @param providerTransactionId null if it could not be read
     * @param payloadWithoutPersonalData null when the body is not valid JSON, because personal
     *                                   data cannot be removed from an unreadable body
     */
    record Parked(String reason, String providerEventId, String providerTransactionId,
                  String payloadWithoutPersonalData) implements MappingResult {
    }
}
