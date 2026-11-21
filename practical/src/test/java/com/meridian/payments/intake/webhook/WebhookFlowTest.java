package com.meridian.payments.intake.webhook;

import static com.meridian.payments.intake.support.TestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.payments.intake.config.Json;
import com.meridian.payments.intake.port.NotificationRecord;
import com.meridian.payments.intake.port.NotificationRecord.ProcessingStatus;
import com.meridian.payments.intake.port.OutgoingMessage;
import com.meridian.payments.intake.stub.InMemoryEventPublisher;
import com.meridian.payments.intake.stub.InMemoryNotificationStore;
import com.meridian.payments.intake.support.MutableClock;

/**
 * The whole intake through HTTP, with the stubs of Service Bus and the integration store:
 * FR1 (401), FR2 (published event), FR4 (duplicates), NFR3 (503 until stored durably),
 * NFR6 (no personal data in the store, the event or the logs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class WebhookFlowTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.EPOCH);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private InMemoryEventPublisher topic;
    @Autowired
    private InMemoryNotificationStore store;

    @BeforeEach
    void reset() {
        topic.reset();
        store.reset();
    }

    private ResultActions postAndina(String sample, String timestamp, String signature, String correlationId)
            throws Exception {
        clock.set(andinaTime(timestamp).plusSeconds(1));
        return mvc.perform(post("/webhooks/andina")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Andina-Timestamp", timestamp)
                .header("X-Andina-Signature", signature)
                .header("X-Correlation-ID", correlationId)
                .content(sample(sample)));
    }

    private ResultActions postAndinaBody(byte[] body, String correlationId) throws Exception {
        clock.set(andinaTime(AND_APPROVED_TIMESTAMP).plusSeconds(1));
        return mvc.perform(post("/webhooks/andina")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Andina-Timestamp", AND_APPROVED_TIMESTAMP)
                .header("X-Andina-Signature", signAndina(ANDINA_SECRET, AND_APPROVED_TIMESTAMP, body))
                .header("X-Correlation-ID", correlationId)
                .content(body));
    }

    private ResultActions postQuetzal(String sample, String signature, String correlationId) throws Exception {
        clock.set(quetzalTime(signature).plusSeconds(1));
        return mvc.perform(post("/webhooks/quetzal")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Quetzal-Signature", signature)
                .header("X-Correlation-ID", correlationId)
                .content(sample(sample)));
    }

    private NotificationRecord row(String correlationId) {
        return store.find(correlationId).orElseThrow();
    }

    @Nested
    @DisplayName("Accepted notifications")
    class Accepted {

        @Test
        @DisplayName("Andina: 200 with an empty body, recorded as RECEIVED, published with the Service Bus properties")
        void andinaIsRecordedAndPublished() throws Exception {
            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, "c-1")
                    .andExpect(status().isOk())
                    .andExpect(content().string(""));

            NotificationRecord row = row("c-1");
            assertThat(row.processingStatus()).isEqualTo(ProcessingStatus.RECEIVED);
            assertThat(row.eventId()).isEqualTo("AND:TRX-CO-5521907:APROBADA");
            assertThat(row.providerStatus()).isEqualTo("APROBADA");
            assertThat(row.receivedAt()).isEqualTo(Instant.parse("2026-11-28T19:03:12Z"));

            assertThat(topic.messages()).singleElement().satisfies(message -> {
                assertThat(message.messageId()).isEqualTo("AND:TRX-CO-5521907:APROBADA");
                assertThat(message.sessionId()).isEqualTo("AND-TRX-CO-5521907");
                assertThat(message.properties())
                        .containsEntry("correlationId", "c-1")
                        .containsEntry("country", "CO")
                        .containsEntry("schemaVersion", "1.0");
                JsonNode event = Json.mapper().readTree(message.body());
                assertThat(event.path("status").asText()).isEqualTo("CAPTURED");
                assertThat(event.path("statusChangedAt").asText()).isEqualTo("2026-11-28T19:03:09Z");
            });
        }

        @Test
        @DisplayName("Quetzal: 200, recorded and published")
        void quetzalIsRecordedAndPublished() throws Exception {
            postQuetzal(QZ_SUCCEEDED, QZ_SUCCEEDED_SIGNATURE, "c-qz")
                    .andExpect(status().isOk())
                    .andExpect(content().string(""));

            assertThat(row("c-qz").processingStatus()).isEqualTo(ProcessingStatus.RECEIVED);
            assertThat(topic.messages()).extracting(OutgoingMessage::messageId).containsExactly("QZ:evt_01JDQ3K8M2X7");
        }

        @Test
        @DisplayName("A correlation ID that is too long or contains a line break is replaced, not stored or logged")
        void replacesInvalidCorrelationId(CapturedOutput output) throws Exception {
            String forged = "abc\nINFO forged log line";
            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, forged)
                    .andExpect(status().isOk());
            postAndina(AND_PENDING, AND_PENDING_TIMESTAMP, AND_PENDING_SIGNATURE, "x".repeat(65))
                    .andExpect(status().isOk());

            assertThat(store.all()).hasSize(2).allSatisfy(row ->
                    assertThat(row.correlationId()).hasSizeLessThanOrEqualTo(64).doesNotContain("\n", "xxxx"));
            assertThat(output.getAll()).doesNotContain("forged log line");
        }

        @Test
        @DisplayName("A missing correlation ID is generated by the intake (assumption 5)")
        void generatesCorrelationIdWhenMissing() throws Exception {
            clock.set(quetzalTime(QZ_SUCCEEDED_SIGNATURE));
            mvc.perform(post("/webhooks/quetzal")
                            .header("Quetzal-Signature", QZ_SUCCEEDED_SIGNATURE)
                            .content(sample(QZ_SUCCEEDED)))
                    .andExpect(status().isOk());

            assertThat(store.all()).singleElement()
                    .satisfies(row -> assertThat(row.correlationId()).isNotBlank());
        }
    }

    @Nested
    @DisplayName("Duplicates (FR4)")
    class Duplicates {

        @Test
        @DisplayName("Andina retry with a new notificacion_id: 200, recorded as DUPLICATE, discarded by Service Bus")
        void andinaRetryIsADuplicate() throws Exception {
            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, "c-1").andExpect(status().isOk());
            postAndina(AND_APPROVED_RETRY, AND_APPROVED_RETRY_TIMESTAMP, AND_APPROVED_RETRY_SIGNATURE, "c-2")
                    .andExpect(status().isOk());

            assertThat(row("c-2").processingStatus()).isEqualTo(ProcessingStatus.DUPLICATE);
            assertThat(row("c-2").providerEventId()).isEqualTo("ntf-c02d9e13");
            assertThat(topic.messages()).hasSize(1);
            assertThat(topic.discardedDuplicates()).isEqualTo(1);
        }

        @Test
        @DisplayName("Quetzal redelivery of the same event: 200, DUPLICATE, one message in the topic")
        void quetzalRedeliveryIsADuplicate() throws Exception {
            postQuetzal(QZ_SUCCEEDED, QZ_SUCCEEDED_SIGNATURE, "c-1").andExpect(status().isOk());
            postQuetzal(QZ_SUCCEEDED, QZ_SUCCEEDED_SIGNATURE, "c-2").andExpect(status().isOk());

            assertThat(row("c-2").processingStatus()).isEqualTo(ProcessingStatus.DUPLICATE);
            assertThat(topic.messages()).hasSize(1);
        }

        @Test
        @DisplayName("Every state of a payment is its own event: PENDIENTE, APROBADA and REVERSADA are all published")
        void lifecycleStatesAreNotDuplicates() throws Exception {
            postAndina(AND_PENDING, AND_PENDING_TIMESTAMP, AND_PENDING_SIGNATURE, "c-1").andExpect(status().isOk());
            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, "c-2").andExpect(status().isOk());
            postAndina(AND_REVERSED, AND_REVERSED_TIMESTAMP, AND_REVERSED_SIGNATURE, "c-3").andExpect(status().isOk());

            assertThat(topic.messages()).extracting(OutgoingMessage::sessionId).containsOnly("AND-TRX-CO-5521907");
            assertThat(topic.messages()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Invalid signatures (FR1)")
    class Rejected {

        @Test
        @DisplayName("Wrong signature: 401, nothing published, REJECTED recorded with metadata only")
        void wrongSignatureIsRejected() throws Exception {
            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_PENDING_SIGNATURE, "c-bad")
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));

            assertThat(topic.messages()).isEmpty();
            NotificationRecord row = row("c-bad");
            assertThat(row.processingStatus()).isEqualTo(ProcessingStatus.REJECTED);
            assertThat(row.provider()).isEqualTo("Andina");
            assertThat(row.reason()).isEqualTo("signature mismatch");
            assertThat(row.providerTransactionId()).as("the body is not trusted").isNull();
            assertThat(row.parkedPayloadJson()).isNull();
        }

        @Test
        @DisplayName("Replay of a valid request after the time window: 401")
        void staleTimestampIsRejected() throws Exception {
            clock.set(quetzalTime(QZ_SUCCEEDED_SIGNATURE).plusSeconds(3600));
            mvc.perform(post("/webhooks/quetzal")
                            .header("Quetzal-Signature", QZ_SUCCEEDED_SIGNATURE)
                            .content(sample(QZ_SUCCEEDED)))
                    .andExpect(status().isUnauthorized());

            assertThat(topic.messages()).isEmpty();
        }

        @Test
        @DisplayName("Missing signature header: 401")
        void missingSignatureIsRejected() throws Exception {
            mvc.perform(post("/webhooks/quetzal").content(sample(QZ_SUCCEEDED)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Store unavailable: still 401, never 503 (rejected requests are recorded best effort)")
        void rejectionDoesNotDependOnTheStore() throws Exception {
            store.setAvailable(false);

            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_PENDING_SIGNATURE, "c-bad")
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Parked notifications")
    class ParkedNotifications {

        @Test
        @DisplayName("Valid signature, invalid reference: 200, PARKED with the payload without personal data, not published")
        void invalidReferenceIsParked() throws Exception {
            byte[] body = utf8(sampleText(AND_APPROVED).replace("POL-CO-000187342", "mi seguro del carro"));

            postAndinaBody(body, "c-park").andExpect(status().isOk());

            assertThat(topic.messages()).isEmpty();
            NotificationRecord row = row("c-park");
            assertThat(row.processingStatus()).isEqualTo(ProcessingStatus.PARKED);
            assertThat(row.reason()).isEqualTo("invalid policy reference");
            assertThat(row.providerTransactionId()).isEqualTo("TRX-CO-5521907");
            assertThat(row.parkedPayloadJson()).contains("mi seguro del carro").doesNotContain("Laura", "1020304050");
        }

        @Test
        @DisplayName("Valid signature, body is not JSON: 200, PARKED without payload")
        void invalidJsonIsParkedWithoutPayload() throws Exception {
            postAndinaBody(utf8("not json"), "c-json").andExpect(status().isOk());

            assertThat(row("c-json").processingStatus()).isEqualTo(ProcessingStatus.PARKED);
            assertThat(row("c-json").parkedPayloadJson()).isNull();
            assertThat(topic.messages()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Unavailable dependencies (NFR3: 200 only once stored durably)")
    class Unavailable {

        @Test
        @DisplayName("Store unavailable: 503 and nothing published, so the provider retries")
        void storeUnavailable() throws Exception {
            store.setAvailable(false);

            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, "c-1")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(""));

            assertThat(topic.messages()).isEmpty();
        }

        @Test
        @DisplayName("Store unavailable for a parked notification: 503, because the store is its only copy")
        void storeUnavailableWhileParking() throws Exception {
            store.setAvailable(false);
            byte[] body = utf8(sampleText(AND_APPROVED).replace("POL-CO-000187342", "mi seguro del carro"));

            postAndinaBody(body, "c-park").andExpect(status().isServiceUnavailable());
        }

        @Test
        @DisplayName("Service Bus unavailable: 503 and the notification is marked FAILED")
        void serviceBusUnavailable() throws Exception {
            topic.setAvailable(false);

            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, "c-1")
                    .andExpect(status().isServiceUnavailable());

            assertThat(row("c-1").processingStatus()).isEqualTo(ProcessingStatus.FAILED);
            assertThat(row("c-1").reason()).isEqualTo("Service Bus unavailable");
        }

        @Test
        @DisplayName("Provider retry after Service Bus recovers: 200 and the event is published (nothing lost)")
        void retryAfterRecoveryIsPublished() throws Exception {
            topic.setAvailable(false);
            postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, "c-1")
                    .andExpect(status().isServiceUnavailable());

            topic.setAvailable(true);
            postAndina(AND_APPROVED_RETRY, AND_APPROVED_RETRY_TIMESTAMP, AND_APPROVED_RETRY_SIGNATURE, "c-2")
                    .andExpect(status().isOk());

            assertThat(row("c-2").processingStatus()).isEqualTo(ProcessingStatus.DUPLICATE);
            assertThat(topic.messages()).extracting(OutgoingMessage::messageId)
                    .containsExactly("AND:TRX-CO-5521907:APROBADA");
        }
    }

    @Test
    @DisplayName("An unknown provider path: 404")
    void unknownProvider() throws Exception {
        mvc.perform(post("/webhooks/austral").content("{}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("NFR6: no personal data in the store, the published events or the logs")
    void noPersonalDataAnywhere(CapturedOutput output) throws Exception {
        postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, "c-1").andExpect(status().isOk());
        postAndinaBody(utf8(sampleText(AND_APPROVED).replace("POL-CO-000187342", "xyz")), "c-2")
                .andExpect(status().isOk());
        postQuetzal(QZ_SUCCEEDED, QZ_SUCCEEDED_SIGNATURE, "c-3").andExpect(status().isOk());
        postAndina(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_PENDING_SIGNATURE, "c-4")
                .andExpect(status().isUnauthorized());

        String[] personalData = {"Laura", "1020304050", "laura.gomez@example.com", "maria.lopez@example.com",
                "tok_qz_9sD2fK"};
        assertThat(store.all().toString()).doesNotContain(personalData);
        for (OutgoingMessage message : topic.messages()) {
            assertThat(new String(message.body(), StandardCharsets.UTF_8)).doesNotContain(personalData);
        }
        assertThat(output.getAll()).contains("c-1", "c-2", "c-3", "c-4").doesNotContain(personalData);
    }
}
