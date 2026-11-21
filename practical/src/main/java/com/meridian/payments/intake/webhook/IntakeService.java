package com.meridian.payments.intake.webhook;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.meridian.payments.intake.event.CanonicalEvent;
import com.meridian.payments.intake.port.EventPublisher;
import com.meridian.payments.intake.port.EventPublisher.PublishException;
import com.meridian.payments.intake.port.NotificationRecord;
import com.meridian.payments.intake.port.NotificationStore;
import com.meridian.payments.intake.port.NotificationStore.StoreUnavailableException;
import com.meridian.payments.intake.port.OutgoingMessage;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.MappingResult.Mapped;
import com.meridian.payments.intake.provider.MappingResult.Parked;
import com.meridian.payments.intake.provider.Provider;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.ProviderAdapter.InvalidSignatureException;
import com.meridian.payments.intake.provider.ProviderAdapter.NotificationContext;

/**
 * The shared intake flow, identical for every provider (SOLUTION_DESIGN.md, section 2):
 * <ol>
 *   <li>verify the signature over the raw bytes; if invalid, record REJECTED (best effort) and answer 401;</li>
 *   <li>translate; if it cannot be translated, record PARKED and answer 200;</li>
 *   <li>record RECEIVED, or DUPLICATE if the event ID is known, publish and answer 200;</li>
 *   <li>if the store or the broker is unavailable, answer 503 so the provider retries (NFR3).</li>
 * </ol>
 * The answer is only sent after the notification is stored durably. Logs contain identifiers
 * only, never the body or personal data (NFR6).
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);
    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final Map<Provider, ProviderAdapter> adapters = new EnumMap<>(Provider.class);
    private final NotificationStore store;
    private final EventPublisher publisher;
    private final Clock clock;

    public IntakeService(List<ProviderAdapter> adapters, NotificationStore store, EventPublisher publisher,
                         Clock clock) {
        adapters.forEach(adapter -> this.adapters.put(adapter.provider(), adapter));
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
    }

    public HttpStatus handle(Provider provider, HttpHeaders headers, byte[] rawBody, String correlationIdHeader) {
        String correlationId = correlationId(correlationIdHeader);
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ProviderAdapter adapter = adapters.get(provider);

        // 1. Authenticity, before the body is parsed (FR1).
        try {
            adapter.verifySignature(headers, rawBody);
        } catch (InvalidSignatureException e) {
            log.warn("Notification rejected provider={} correlationId={} reason={}",
                    provider.displayName(), correlationId, e.getMessage());
            recordRejectedBestEffort(NotificationRecord.rejected(correlationId, provider, adapter.country(),
                    e.getMessage(), receivedAt));
            return HttpStatus.UNAUTHORIZED;
        }

        // 2. Translation into the canonical event (FR2).
        MappingResult result = adapter.map(rawBody, new NotificationContext(correlationId, receivedAt));

        try {
            return switch (result) {
                case Parked parked -> park(provider, adapter.country(), correlationId, receivedAt, parked);
                case Mapped mapped -> recordAndPublish(mapped);
            };
        } catch (StoreUnavailableException e) {
            log.error("Integration store unavailable provider={} correlationId={}", provider.displayName(), correlationId);
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    private HttpStatus park(Provider provider, String country, String correlationId, Instant receivedAt,
                            Parked parked) {
        store.insert(NotificationRecord.parked(correlationId, provider, country, parked.providerEventId(),
                parked.providerTransactionId(), parked.reason(), parked.payloadWithoutPersonalData(), receivedAt));
        log.warn("Notification parked provider={} correlationId={} providerEventId={} reason={}",
                provider.displayName(), correlationId, parked.providerEventId(), parked.reason());
        // 200: the notification is authentic and now stored; a retry would fail the same way.
        return HttpStatus.OK;
    }

    private HttpStatus recordAndPublish(Mapped mapped) {
        CanonicalEvent event = mapped.event();

        // 3. Record first (FR4: a known event ID is marked as duplicate), then publish.
        boolean duplicate = store.existsByEventId(event.eventId());
        store.insert(NotificationRecord.accepted(event, mapped.providerStatus(), duplicate));

        try {
            // Always published, even a duplicate: if the first attempt failed to publish, this copy
            // gets through; otherwise Service Bus duplicate detection discards it.
            publisher.publish(OutgoingMessage.from(event));
        } catch (PublishException e) {
            log.error("Service Bus unavailable provider={} correlationId={} eventId={}",
                    event.provider(), event.correlationId(), event.eventId());
            markFailedBestEffort(event.correlationId());
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        log.info("Notification accepted provider={} correlationId={} eventId={} paymentKey={} status={} duplicate={}",
                event.provider(), event.correlationId(), event.eventId(), event.paymentKey(), event.status(), duplicate);
        return HttpStatus.OK;
    }

    /**
     * Uses the API Manager's correlation ID, or generates one if it is missing (assumption 5). A value
     * that is not a plain identifier of at most 64 characters is replaced too: it would not fit the
     * {@code correlation_id} column, and line breaks in it could forge log lines.
     */
    private static String correlationId(String header) {
        if (header != null && CORRELATION_ID.matcher(header).matches()) {
            return header;
        }
        String generated = UUID.randomUUID().toString();
        if (header != null) {
            log.warn("Invalid correlation ID header replaced correlationId={}", generated);
        }
        return generated;
    }

    /** A database problem must not turn a 401 into a 503 (ADR-0002). */
    private void recordRejectedBestEffort(NotificationRecord rejected) {
        try {
            store.insert(rejected);
        } catch (RuntimeException e) {
            log.warn("Rejected notification not recorded correlationId={}", rejected.correlationId());
        }
    }

    private void markFailedBestEffort(String correlationId) {
        try {
            store.markFailed(correlationId, "Service Bus unavailable");
        } catch (RuntimeException e) {
            log.warn("Notification could not be marked as failed correlationId={}", correlationId);
        }
    }
}
