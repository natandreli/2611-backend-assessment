package com.meridian.payments.intake.port;

import java.time.Instant;

import com.meridian.payments.intake.event.CanonicalEvent;
import com.meridian.payments.intake.provider.Provider;

/**
 * One row of the {@code notification} table: one per request received. Which columns are filled
 * depends on how far the notification got (SOLUTION_DESIGN.md, section 7). Never holds payer
 * personal data.
 */
public record NotificationRecord(
        String correlationId,
        String provider,
        String country,
        String providerEventId,
        String providerTransactionId,
        String eventId,
        String paymentKey,
        String providerStatus,
        String policyNumber,
        String amount,
        String refundedAmount,
        String currency,
        ProcessingStatus processingStatus,
        String reason,
        String parkedPayloadJson,
        Instant receivedAt) {

    /** Values set by the intake. The writer later sets APPLIED, STALE, DUPLICATE or DEAD_LETTERED. */
    public enum ProcessingStatus {
        RECEIVED,
        DUPLICATE,
        REJECTED,
        PARKED,
        FAILED
    }

    /** Invalid signature: metadata only, because the body is not trusted. */
    public static NotificationRecord rejected(String correlationId, Provider provider, String country,
                                              String reason, Instant receivedAt) {
        return new NotificationRecord(correlationId, provider.displayName(), country, null, null, null, null,
                null, null, null, null, null, ProcessingStatus.REJECTED, reason, null, receivedAt);
    }

    /** Authentic but not mappable: identifiers when readable, and the payload without personal data. */
    public static NotificationRecord parked(String correlationId, Provider provider, String country,
                                            String providerEventId, String providerTransactionId,
                                            String reason, String parkedPayloadJson, Instant receivedAt) {
        return new NotificationRecord(correlationId, provider.displayName(), country, providerEventId,
                providerTransactionId, null, null, null, null, null, null, null, ProcessingStatus.PARKED, reason,
                parkedPayloadJson, receivedAt);
    }

    /** Mapped: RECEIVED, or DUPLICATE if its event ID was already recorded. */
    public static NotificationRecord accepted(CanonicalEvent event, String providerStatus, boolean duplicate) {
        return new NotificationRecord(event.correlationId(), event.provider(), event.country(),
                event.providerEventId(), event.providerTransactionId(), event.eventId(), event.paymentKey(),
                providerStatus, event.policyNumber(), event.amount(), event.refundedAmount(), event.currency(),
                duplicate ? ProcessingStatus.DUPLICATE : ProcessingStatus.RECEIVED, null, null, event.receivedAt());
    }

    public NotificationRecord withFailure(String failureReason) {
        return new NotificationRecord(correlationId, provider, country, providerEventId, providerTransactionId,
                eventId, paymentKey, providerStatus, policyNumber, amount, refundedAmount, currency,
                ProcessingStatus.FAILED, failureReason, parkedPayloadJson, receivedAt);
    }
}
