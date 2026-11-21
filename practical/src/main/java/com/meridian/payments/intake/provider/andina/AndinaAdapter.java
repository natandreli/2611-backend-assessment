package com.meridian.payments.intake.provider.andina;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.config.IntakeProperties;
import com.meridian.payments.intake.config.IntakeProperties.ProviderSettings;
import com.meridian.payments.intake.event.CanonicalEvent;
import com.meridian.payments.intake.event.CanonicalEventValidator;
import com.meridian.payments.intake.event.PaymentStatus;
import com.meridian.payments.intake.port.SecretProvider;
import com.meridian.payments.intake.provider.Amounts;
import com.meridian.payments.intake.provider.JsonProviderAdapter;
import com.meridian.payments.intake.provider.MappingResult.Mapped;
import com.meridian.payments.intake.provider.ParkException;
import com.meridian.payments.intake.provider.Provider;

/**
 * Andina Pay (Colombia), API version 2.3. Every retry carries a new {@code notificacion_id}, so
 * the business event is identified by {@code transaccion.id} + {@code transaccion.estado}.
 * Field and state mapping: SOLUTION_DESIGN.md, section 3.
 */
@Component
public class AndinaAdapter extends JsonProviderAdapter {

    static final String TIMESTAMP_HEADER = "X-Andina-Timestamp";
    static final String SIGNATURE_HEADER = "X-Andina-Signature";

    private final ProviderSettings settings;
    private final SecretProvider secrets;
    private final AndinaSignatureVerifier verifier;

    public AndinaAdapter(IntakeProperties properties, SecretProvider secrets, Clock clock,
                         CanonicalEventValidator validator) {
        super(validator);
        this.settings = properties.provider(Provider.ANDINA.pathSegment());
        this.secrets = secrets;
        this.verifier = new AndinaSignatureVerifier(clock, settings.timestampTolerance());
    }

    @Override
    public Provider provider() {
        return Provider.ANDINA;
    }

    @Override
    public String country() {
        return settings.country();
    }

    @Override
    public void verifySignature(HttpHeaders headers, byte[] rawBody) {
        verifier.verify(headers.getFirst(TIMESTAMP_HEADER), headers.getFirst(SIGNATURE_HEADER), rawBody,
                secrets.activeSecrets(settings.secretName()));
    }

    @Override
    protected Mapped translate(JsonNode root, NotificationContext context) {
        String notificationId = requiredText(root, "notificacion_id");
        JsonNode transaction = root.path("transaccion");
        String transactionId = requiredText(transaction, "id");
        String state = requiredText(transaction, "estado");
        String currency = requiredText(transaction, "moneda");
        String amount = Amounts.fromMajorUnits(requiredText(transaction, "valor"), currency);

        String policyNumber = PolicyReferenceNormalizer.normalize(optionalText(transaction, "referencia"))
                .orElseThrow(() -> new ParkException("invalid policy reference"));

        PaymentStatus status;
        String refundedAmount = null;
        switch (state) {
            case "PENDIENTE" -> status = PaymentStatus.PENDING;
            case "APROBADA" -> status = PaymentStatus.CAPTURED;
            case "RECHAZADA" -> status = PaymentStatus.FAILED;
            case "REVERSADA" -> {
                status = PaymentStatus.REFUNDED;
                refundedAmount = amount; // a reversal is always for the full amount (assumption 8)
            }
            default -> throw new ParkException("unknown transaction state");
        }

        CanonicalEvent event = new CanonicalEvent(
                CanonicalEvent.SCHEMA_VERSION,
                "AND:" + transactionId + ":" + state,
                context.correlationId(),
                "AND-" + transactionId,
                Provider.ANDINA.displayName(),
                settings.country(),
                transactionId,
                notificationId,
                policyNumber,
                status,
                toUtc(requiredText(transaction, "fecha_transaccion")),
                amount,
                refundedAmount,
                currency,
                optionalText(transaction, "medio_pago"),
                context.receivedAt());
        return new Mapped(event, state);
    }

    private static Instant toUtc(String isoWithOffset) {
        try {
            return OffsetDateTime.parse(isoWithOffset).toInstant();
        } catch (DateTimeParseException e) {
            throw new ParkException("invalid field: fecha_transaccion");
        }
    }

    @Override
    protected String providerEventId(JsonNode root) {
        return optionalText(root, "notificacion_id");
    }

    @Override
    protected String providerTransactionId(JsonNode root) {
        return optionalText(root.path("transaccion"), "id");
    }

    @Override
    protected void removePersonalData(ObjectNode copy) {
        if (copy.path("transaccion") instanceof ObjectNode transaction) {
            transaction.remove("pagador");
        }
    }
}
