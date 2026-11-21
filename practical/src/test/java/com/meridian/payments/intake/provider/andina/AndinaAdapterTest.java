package com.meridian.payments.intake.provider.andina;

import static com.meridian.payments.intake.support.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.meridian.payments.intake.config.Json;
import com.meridian.payments.intake.event.CanonicalEvent;
import com.meridian.payments.intake.event.PaymentStatus;
import com.meridian.payments.intake.provider.MappingResult;
import com.meridian.payments.intake.provider.MappingResult.Mapped;
import com.meridian.payments.intake.provider.MappingResult.Parked;
import com.meridian.payments.intake.provider.ProviderAdapter.NotificationContext;
import com.meridian.payments.intake.support.Adapters;

/** FR2 and FR4 for Andina, including the API 2.3 reference change behind INC-0412. */
class AndinaAdapterTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-11-28T19:03:12Z");
    private static final NotificationContext CONTEXT = new NotificationContext("corr-and-1", RECEIVED_AT);

    private final AndinaAdapter adapter = Adapters.andina(Clock.systemUTC());

    private CanonicalEvent mapped(byte[] body) {
        MappingResult result = adapter.map(body, CONTEXT);
        assertThat(result).isInstanceOf(Mapped.class);
        return ((Mapped) result).event();
    }

    private byte[] approvedWithReference(String reference) {
        return utf8(sampleText(AND_APPROVED).replace("\"referencia\": \"POL-CO-000187342\"",
                "\"referencia\": \"" + reference + "\""));
    }

    @Test
    @DisplayName("APROBADA maps to CAPTURED, matching the example in SOLUTION_DESIGN.md section 3")
    void mapsApprovedTransaction() {
        CanonicalEvent event = mapped(sample(AND_APPROVED));

        assertThat(event.eventId()).isEqualTo("AND:TRX-CO-5521907:APROBADA");
        assertThat(event.paymentKey()).isEqualTo("AND-TRX-CO-5521907");
        assertThat(event.provider()).isEqualTo("Andina");
        assertThat(event.country()).isEqualTo("CO");
        assertThat(event.providerTransactionId()).isEqualTo("TRX-CO-5521907");
        assertThat(event.providerEventId()).isEqualTo("ntf-8f1c2a77");
        assertThat(event.policyNumber()).isEqualTo("POL-CO-000187342");
        assertThat(event.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(event.statusChangedAt()).as("14:03:09-05:00 in UTC")
                .isEqualTo(Instant.parse("2026-11-28T19:03:09Z"));
        assertThat(event.amount()).isEqualTo("185000.00");
        assertThat(event.currency()).isEqualTo("COP");
        assertThat(event.paymentMethod()).isEqualTo("PSE");
    }

    @Test
    @DisplayName("PENDIENTE maps to PENDING")
    void mapsPendingTransaction() {
        assertThat(mapped(sample(AND_PENDING)).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("REVERSADA maps to REFUNDED for the full amount (assumption 8), same payment key")
    void mapsReversal() {
        CanonicalEvent event = mapped(sample(AND_REVERSED));

        assertThat(event.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(event.refundedAmount()).isEqualTo("185000.00");
        assertThat(event.paymentKey()).isEqualTo("AND-TRX-CO-5521907");
        assertThat(event.statusChangedAt()).isEqualTo(Instant.parse("2026-12-03T14:15:40Z"));
    }

    @Test
    @DisplayName("FR4: a retry has a new notificacion_id but the same event ID")
    void retryKeepsTheSameEventId() {
        CanonicalEvent original = mapped(sample(AND_APPROVED));
        CanonicalEvent retry = mapped(sample(AND_APPROVED_RETRY));

        assertThat(retry.providerEventId()).isEqualTo("ntf-c02d9e13").isNotEqualTo(original.providerEventId());
        assertThat(retry.eventId()).isEqualTo(original.eventId());
    }

    @Test
    @DisplayName("Different states of the same transaction are different events of the same payment")
    void statesAreDifferentEvents() {
        CanonicalEvent pending = mapped(sample(AND_PENDING));
        CanonicalEvent approved = mapped(sample(AND_APPROVED));

        assertThat(pending.eventId()).isNotEqualTo(approved.eventId());
        assertThat(pending.paymentKey()).isEqualTo(approved.paymentKey());
    }

    @ParameterizedTest(name = "\"{0}\" becomes {1}")
    @CsvSource({
            "' pol-co-000198004 ', POL-CO-000198004",
            "000215560, POL-CO-000215560",
            "POL-CO-000187342, POL-CO-000187342"
    })
    @DisplayName("API 2.3: the reference typed by the payer is normalized (assumption 3)")
    void normalizesReference(String reference, String expected) {
        assertThat(mapped(approvedWithReference(reference)).policyNumber()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource({"mi seguro del carro", "POL-CO-18734", "00021556", "POL-MX-000412785", "CO-000187342"})
    @DisplayName("API 2.3: a reference that is still invalid is parked, never guessed")
    void parksInvalidReference(String reference) {
        MappingResult result = adapter.map(approvedWithReference(reference), CONTEXT);

        assertThat(result).isInstanceOfSatisfying(Parked.class, parked -> {
            assertThat(parked.reason()).isEqualTo("invalid policy reference");
            assertThat(parked.providerEventId()).isEqualTo("ntf-8f1c2a77");
            assertThat(parked.providerTransactionId()).isEqualTo("TRX-CO-5521907");
            assertThat(parked.payloadWithoutPersonalData())
                    .contains(reference)
                    .doesNotContain("Laura", "1020304050", "laura.gomez@example.com", "pagador");
        });
    }

    @Test
    @DisplayName("The canonical event carries no personal data (NFR6)")
    void dropsPersonalData() throws Exception {
        String json = Json.mapper().writeValueAsString(mapped(sample(AND_APPROVED)));

        assertThat(json).doesNotContain("Laura", "1020304050", "laura.gomez@example.com", "pagador");
    }

    @Test
    @DisplayName("Unknown fields added by API 2.3 (transaccion.canal) are ignored")
    void ignoresNewOptionalField() {
        byte[] body = utf8(sampleText(AND_APPROVED).replace("\"medio_pago\": \"PSE\",",
                "\"medio_pago\": \"PSE\", \"canal\": \"APP\","));

        assertThat(mapped(body).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    @DisplayName("An amount above the Salesforce limit (14 integer digits) is parked by the contract check")
    void parksAmountAboveSalesforceLimit() {
        byte[] body = utf8(sampleText(AND_APPROVED).replace("\"185000.00\"", "\"123456789012345.00\""));

        assertThat(adapter.map(body, CONTEXT)).isInstanceOfSatisfying(Parked.class,
                parked -> assertThat(parked.reason()).startsWith("canonical event does not match the contract"));
    }

    @Test
    @DisplayName("A parked notification keeps an identifier only if it fits its column (80 characters)")
    void parkedIdentifierLongerThanColumnIsLeftEmpty() {
        byte[] body = utf8(sampleText(AND_APPROVED)
                .replace("ntf-8f1c2a77", "n".repeat(81))
                .replace("POL-CO-000187342", "mi seguro del carro"));

        assertThat(adapter.map(body, CONTEXT)).isInstanceOfSatisfying(Parked.class, parked -> {
            assertThat(parked.providerEventId()).isNull();
            assertThat(parked.providerTransactionId()).isEqualTo("TRX-CO-5521907");
            assertThat(parked.payloadWithoutPersonalData()).contains("n".repeat(81));
        });
    }

    @Test
    @DisplayName("An unknown state is parked")
    void parksUnknownState() {
        byte[] body = utf8(sampleText(AND_APPROVED).replace("APROBADA", "EN_DISPUTA"));

        assertThat(adapter.map(body, CONTEXT)).isInstanceOfSatisfying(Parked.class,
                parked -> assertThat(parked.reason()).isEqualTo("unknown transaction state"));
    }

    @Test
    @DisplayName("An amount with more decimals than COP allows is parked")
    void parksInvalidAmount() {
        byte[] body = utf8(sampleText(AND_APPROVED).replace("\"185000.00\"", "\"185000.005\""));

        assertThat(adapter.map(body, CONTEXT)).isInstanceOfSatisfying(Parked.class,
                parked -> assertThat(parked.reason()).isEqualTo("amount has more decimals than its currency allows"));
    }
}
