package com.meridian.payments.intake.provider.quetzal;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

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
 * Quetzal Pagos (Mexico), API version 2025-06-01. Field and state mapping: SOLUTION_DESIGN.md,
 * section 3.
 */
@Component
public class QuetzalAdapter extends JsonProviderAdapter {

    static final String SIGNATURE_HEADER = "Quetzal-Signature";
    private static final Pattern POLICY_NUMBER = Pattern.compile("^POL-MX-[0-9]{9}$");

    private final ProviderSettings settings;
    private final SecretProvider secrets;
    private final QuetzalSignatureVerifier verifier;

    public QuetzalAdapter(IntakeProperties properties, SecretProvider secrets, Clock clock,
                          CanonicalEventValidator validator) {
        super(validator);
        this.settings = properties.provider(Provider.QUETZAL.pathSegment());
        this.secrets = secrets;
        this.verifier = new QuetzalSignatureVerifier(clock, settings.timestampTolerance());
    }

    @Override
    public Provider provider() {
        return Provider.QUETZAL;
    }

    @Override
    public String country() {
        return settings.country();
    }

    @Override
    public void verifySignature(HttpHeaders headers, byte[] rawBody) {
        verifier.verify(headers.getFirst(SIGNATURE_HEADER), rawBody, secrets.activeSecrets(settings.secretName()));
    }

    @Override
    protected Mapped translate(JsonNode root, NotificationContext context) {
        String eventId = requiredText(root, "id");
        String type = requiredText(root, "type");
        JsonNode charge = root.path("data").path("charge");
        String chargeId = requiredText(charge, "id");
        String currency = requiredText(charge, "currency").toUpperCase(Locale.ROOT);
        long amountMinor = requiredLong(charge, "amount");

        String policyNumber = requiredText(charge, "reference");
        if (!POLICY_NUMBER.matcher(policyNumber).matches()) {
            throw new ParkException("invalid policy reference");
        }

        PaymentStatus status;
        Instant statusChangedAt;
        String refundedAmount = null;
        switch (type) {
            case "charge.succeeded" -> {
                status = PaymentStatus.CAPTURED;
                statusChangedAt = Instant.ofEpochSecond(requiredLong(charge, "paid_at"));
            }
            case "charge.failed" -> {
                status = PaymentStatus.FAILED;
                // charge.failed has no failure time: the event time is used (assumption 11).
                statusChangedAt = Instant.ofEpochSecond(requiredLong(root, "created"));
            }
            case "charge.refunded" -> {
                long refundedMinor = requiredLong(charge, "amount_refunded");
                if (refundedMinor <= 0 || refundedMinor > amountMinor) {
                    throw new ParkException("refunded amount out of range");
                }
                // amount_refunded is cumulative (assumption 7).
                status = refundedMinor == amountMinor ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
                refundedAmount = Amounts.fromMinorUnits(refundedMinor, currency);
                statusChangedAt = Instant.ofEpochSecond(requiredLong(charge, "refunded_at"));
            }
            default -> throw new ParkException("unknown event type");
        }

        CanonicalEvent event = new CanonicalEvent(
                CanonicalEvent.SCHEMA_VERSION,
                "QZ:" + eventId,
                context.correlationId(),
                "QZ-" + chargeId,
                Provider.QUETZAL.displayName(),
                settings.country(),
                chargeId,
                eventId,
                policyNumber,
                status,
                statusChangedAt,
                Amounts.fromMinorUnits(amountMinor, currency),
                refundedAmount,
                currency,
                optionalText(charge.path("payment_method"), "type"),
                context.receivedAt());
        return new Mapped(event, type);
    }

    @Override
    protected String providerEventId(JsonNode root) {
        return optionalText(root, "id");
    }

    @Override
    protected String providerTransactionId(JsonNode root) {
        return optionalText(root.path("data").path("charge"), "id");
    }

    @Override
    protected void removePersonalData(ObjectNode copy) {
        JsonNode charge = copy.path("data").path("charge");
        if (charge instanceof ObjectNode chargeNode) {
            chargeNode.remove("customer");
            if (chargeNode.path("payment_method") instanceof ObjectNode paymentMethod) {
                paymentMethod.remove("token");
            }
        }
    }
}
