package com.meridian.payments.intake.event;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Canonical payment event, version 1.0. Contract: docs/api/events/premium-payment.schema.json.
 * It never carries payer personal data (NFR6). Optional fields are omitted when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalEvent(
        String schemaVersion,
        String eventId,
        String correlationId,
        String paymentKey,
        String provider,
        String country,
        String providerTransactionId,
        String providerEventId,
        String policyNumber,
        PaymentStatus status,
        Instant statusChangedAt,
        String amount,
        String refundedAmount,
        String currency,
        String paymentMethod,
        Instant receivedAt) {

    public static final String SCHEMA_VERSION = "1.0";
}
