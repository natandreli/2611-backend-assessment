package com.meridian.payments.intake.provider.quetzal;

import static com.meridian.payments.intake.support.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.meridian.payments.intake.config.Json;
import com.meridian.payments.intake.event.CanonicalEvent;
import com.meridian.payments.intake.event.PaymentStatus;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.MappingResult.Mapped;
import com.meridian.payments.intake.provider.MappingResult.Parked;
import com.meridian.payments.intake.provider.ProviderAdapter.NotificationContext;
import com.meridian.payments.intake.support.Adapters;

/** FR2 for Quetzal: field and state mapping of SOLUTION_DESIGN.md, section 3. */
class QuetzalAdapterTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-11-28T17:00:04Z");
    private static final NotificationContext CONTEXT = new NotificationContext("corr-qz-1", RECEIVED_AT);

    private final QuetzalAdapter adapter = Adapters.quetzal(Clock.systemUTC());

    private CanonicalEvent mapped(byte[] body) {
        MappingResult result = adapter.map(body, CONTEXT);
        assertThat(result).isInstanceOf(Mapped.class);
        return ((Mapped) result).event();
    }

    @Test
    @DisplayName("charge.succeeded maps to CAPTURED, amount in major units, currency in upper case")
    void mapsCapturedCharge() {
        CanonicalEvent event = mapped(sample(QZ_SUCCEEDED));

        assertThat(event.schemaVersion()).isEqualTo("1.0");
        assertThat(event.eventId()).isEqualTo("QZ:evt_01JDQ3K8M2X7");
        assertThat(event.paymentKey()).isEqualTo("QZ-ch_7Hk2Lm9QpZ");
        assertThat(event.correlationId()).isEqualTo("corr-qz-1");
        assertThat(event.provider()).isEqualTo("Quetzal");
        assertThat(event.country()).isEqualTo("MX");
        assertThat(event.providerTransactionId()).isEqualTo("ch_7Hk2Lm9QpZ");
        assertThat(event.providerEventId()).isEqualTo("evt_01JDQ3K8M2X7");
        assertThat(event.policyNumber()).isEqualTo("POL-MX-000412785");
        assertThat(event.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(event.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795888796)); // paid_at
        assertThat(event.amount()).isEqualTo("1250.00");
        assertThat(event.refundedAmount()).isNull();
        assertThat(event.currency()).isEqualTo("MXN");
        assertThat(event.paymentMethod()).isEqualTo("card");
        assertThat(event.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    @DisplayName("charge.failed maps to FAILED, with the event time as statusChangedAt (assumption 11)")
    void mapsFailedCharge() {
        CanonicalEvent event = mapped(sample(QZ_FAILED));

        assertThat(event.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(event.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795889400)); // created
        assertThat(event.amount()).isEqualTo("980.00");
    }

    @Test
    @DisplayName("charge.refunded with amount_refunded = amount maps to REFUNDED, with refunded_at")
    void mapsFullRefund() {
        CanonicalEvent event = mapped(sample(QZ_REFUNDED));

        assertThat(event.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(event.refundedAmount()).isEqualTo("1250.00");
        assertThat(event.statusChangedAt()).isEqualTo(Instant.ofEpochSecond(1795975195));
        assertThat(event.eventId()).isEqualTo("QZ:evt_01JDQ9W4R6Y2");
        assertThat(event.paymentKey()).as("same charge, same Salesforce record").isEqualTo("QZ-ch_7Hk2Lm9QpZ");
    }

    @Test
    @DisplayName("charge.refunded with amount_refunded < amount maps to PARTIALLY_REFUNDED (assumption 7)")
    void mapsPartialRefund() {
        byte[] partial = utf8(sampleText(QZ_REFUNDED)
                .replace("\"amount_refunded\": 125000", "\"amount_refunded\": 25000"));

        CanonicalEvent event = mapped(partial);

        assertThat(event.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(event.amount()).isEqualTo("1250.00");
        assertThat(event.refundedAmount()).isEqualTo("250.00");
    }

    @Test
    @DisplayName("The canonical event carries no personal data or payment token (NFR6)")
    void dropsPersonalData() throws Exception {
        String json = Json.mapper().writeValueAsString(mapped(sample(QZ_SUCCEEDED)));

        assertThat(json).doesNotContain("maria.lopez@example.com", "tok_qz_9sD2fK", "customer", "token");
    }

    @Test
    @DisplayName("An invalid policy reference is parked, never guessed; the parked payload has no personal data")
    void parksInvalidReference() {
        byte[] body = utf8(sampleText(QZ_SUCCEEDED).replace("POL-MX-000412785", "mi poliza"));

        MappingResult result = adapter.map(body, CONTEXT);

        assertThat(result).isInstanceOf(Parked.class);
        Parked parked = (Parked) result;
        assertThat(parked.reason()).isEqualTo("invalid policy reference");
        assertThat(parked.providerEventId()).isEqualTo("evt_01JDQ3K8M2X7");
        assertThat(parked.providerTransactionId()).isEqualTo("ch_7Hk2Lm9QpZ");
        assertThat(parked.payloadWithoutPersonalData())
                .contains("mi poliza", "\"amount\":125000")
                .doesNotContain("maria.lopez@example.com", "tok_qz_9sD2fK");
    }

    @Test
    @DisplayName("An unknown event type is parked")
    void parksUnknownType() {
        byte[] body = utf8(sampleText(QZ_SUCCEEDED).replace("charge.succeeded", "charge.disputed"));

        assertThat(adapter.map(body, CONTEXT)).isInstanceOfSatisfying(Parked.class,
                parked -> assertThat(parked.reason()).isEqualTo("unknown event type"));
    }

    @Test
    @DisplayName("A refunded amount greater than the charge is parked")
    void parksRefundAboveAmount() {
        byte[] body = utf8(sampleText(QZ_REFUNDED)
                .replace("\"amount_refunded\": 125000", "\"amount_refunded\": 999999"));

        assertThat(adapter.map(body, CONTEXT)).isInstanceOfSatisfying(Parked.class,
                parked -> assertThat(parked.reason()).isEqualTo("refunded amount out of range"));
    }

    @Test
    @DisplayName("A body that is not JSON is parked without storing it (personal data cannot be removed)")
    void parksInvalidJsonWithoutPayload() {
        assertThat(adapter.map(utf8("{\"id\": \"evt_1\", "), CONTEXT)).isInstanceOfSatisfying(Parked.class,
                parked -> {
                    assertThat(parked.reason()).isEqualTo("body is not a valid JSON object");
                    assertThat(parked.payloadWithoutPersonalData()).isNull();
                });
    }

    @Test
    @DisplayName("A missing required field is parked with its name as reason")
    void parksMissingField() {
        byte[] body = utf8(sampleText(QZ_SUCCEEDED).replace("\"paid_at\": 1795888796", "\"paid_at\": null"));

        assertThat(adapter.map(body, CONTEXT)).isInstanceOfSatisfying(Parked.class,
                parked -> assertThat(parked.reason()).isEqualTo("missing or invalid field: paid_at"));
    }

    @Test
    @DisplayName("Unknown fields are ignored (tolerant reader)")
    void ignoresUnknownFields() {
        byte[] body = utf8(sampleText(QZ_SUCCEEDED).replace("\"type\": \"charge.succeeded\",",
                "\"type\": \"charge.succeeded\", \"livemode\": true,"));

        assertThat(mapped(body).status()).isEqualTo(PaymentStatus.CAPTURED);
    }
}
