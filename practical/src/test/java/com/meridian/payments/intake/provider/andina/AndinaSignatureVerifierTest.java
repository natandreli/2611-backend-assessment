package com.meridian.payments.intake.provider.andina;

import static com.meridian.payments.intake.support.TestData.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.meridian.payments.intake.provider.ProviderAdapter.InvalidSignatureException;

/** FR1 for Andina, proven with the provider's official test vectors. */
class AndinaSignatureVerifierTest {

    private static final Duration TOLERANCE = Duration.ofMinutes(10);
    private static final List<String> SECRETS = List.of(ANDINA_SECRET);

    private static AndinaSignatureVerifier verifierAt(Instant now) {
        return new AndinaSignatureVerifier(Clock.fixed(now, ZoneOffset.UTC), TOLERANCE);
    }

    static Stream<Arguments> officialVectors() {
        return Stream.of(
                Arguments.of(AND_PENDING, AND_PENDING_TIMESTAMP, AND_PENDING_SIGNATURE),
                Arguments.of(AND_APPROVED, AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE),
                Arguments.of(AND_APPROVED_RETRY, AND_APPROVED_RETRY_TIMESTAMP, AND_APPROVED_RETRY_SIGNATURE),
                Arguments.of(AND_REVERSED, AND_REVERSED_TIMESTAMP, AND_REVERSED_SIGNATURE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("officialVectors")
    @DisplayName("Accepts every official test vector")
    void acceptsOfficialVectors(String sample, String timestamp, String signature) {
        assertThatCode(() -> verifierAt(andinaTime(timestamp)).verify(timestamp, signature, sample(sample), SECRETS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Rejects a body changed by a single byte: the signature covers the raw bytes")
    void rejectsTamperedBody() {
        byte[] body = sample(AND_APPROVED);
        byte[] tampered = Arrays.copyOf(body, body.length);
        tampered[tampered.length - 2] = (byte) ' ';
        assertThatThrownBy(() -> verifierAt(andinaTime(AND_APPROVED_TIMESTAMP))
                .verify(AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, tampered, SECRETS))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("signature mismatch");
    }

    @Test
    @DisplayName("Rejects a valid signature replayed with another timestamp: the timestamp is signed")
    void rejectsChangedTimestamp() {
        String otherTimestamp = "2026-11-28T14:03:12-05:00";
        assertThatThrownBy(() -> verifierAt(andinaTime(otherTimestamp))
                .verify(otherTimestamp, AND_APPROVED_SIGNATURE, sample(AND_APPROVED), SECRETS))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("signature mismatch");
    }

    @Test
    @DisplayName("Rejects a valid signature whose timestamp is more than 10 minutes away")
    void rejectsTimestampOutsideWindow() {
        Instant now = andinaTime(AND_APPROVED_TIMESTAMP).plus(Duration.ofMinutes(10)).plusSeconds(1);
        assertThatThrownBy(() -> verifierAt(now)
                .verify(AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, sample(AND_APPROVED), SECRETS))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("timestamp outside the allowed window");
    }

    @Test
    @DisplayName("Secret rotation: accepts a signature made with either of two active secrets")
    void acceptsEitherActiveSecret() {
        assertThatCode(() -> verifierAt(andinaTime(AND_APPROVED_TIMESTAMP))
                .verify(AND_APPROVED_TIMESTAMP, AND_APPROVED_SIGNATURE, sample(AND_APPROVED),
                        List.of("new_secret", ANDINA_SECRET)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Rejects a signature that is not Base64 (for example a hex signature)")
    void rejectsNonBase64Signature() {
        assertThatThrownBy(() -> verifierAt(andinaTime(AND_APPROVED_TIMESTAMP))
                .verify(AND_APPROVED_TIMESTAMP, "not base64!", sample(AND_APPROVED), SECRETS))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("malformed signature");
    }

    @Test
    @DisplayName("Rejects missing headers and a malformed timestamp")
    void rejectsMissingOrMalformedHeaders() {
        Instant now = andinaTime(AND_APPROVED_TIMESTAMP);
        byte[] body = sample(AND_APPROVED);
        assertThatThrownBy(() -> verifierAt(now).verify(null, AND_APPROVED_SIGNATURE, body, SECRETS))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifierAt(now).verify(AND_APPROVED_TIMESTAMP, null, body, SECRETS))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifierAt(now).verify("28/11/2026 14:03", AND_APPROVED_SIGNATURE, body, SECRETS))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("malformed signature timestamp");
    }
}
