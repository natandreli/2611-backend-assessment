package com.meridian.payments.intake.provider.quetzal;

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
import org.junit.jupiter.params.provider.ValueSource;

import com.meridian.payments.intake.provider.ProviderAdapter.InvalidSignatureException;

/** FR1 for Quetzal, proven with the provider's official test vectors. */
class QuetzalSignatureVerifierTest {

    private static final Duration TOLERANCE = Duration.ofSeconds(300);
    private static final List<String> SECRETS = List.of(QUETZAL_SECRET);

    private static QuetzalSignatureVerifier verifierAt(Instant now) {
        return new QuetzalSignatureVerifier(Clock.fixed(now, ZoneOffset.UTC), TOLERANCE);
    }

    static Stream<Arguments> officialVectors() {
        return Stream.of(
                Arguments.of(QZ_SUCCEEDED, QZ_SUCCEEDED_SIGNATURE),
                Arguments.of(QZ_FAILED, QZ_FAILED_SIGNATURE),
                Arguments.of(QZ_REFUNDED, QZ_REFUNDED_SIGNATURE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("officialVectors")
    @DisplayName("Accepts every official test vector")
    void acceptsOfficialVectors(String sample, String header) {
        assertThatCode(() -> verifierAt(quetzalTime(header)).verify(header, sample(sample), SECRETS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Rejects a body changed by a single byte: the signature covers the raw bytes")
    void rejectsTamperedBody() {
        byte[] body = sample(QZ_SUCCEEDED);
        byte[] tampered = Arrays.copyOf(body, body.length);
        tampered[tampered.length - 2] = (byte) ' '; // like whitespace added when JSON is re-serialized
        assertThatThrownBy(() -> verifierAt(quetzalTime(QZ_SUCCEEDED_SIGNATURE))
                .verify(QZ_SUCCEEDED_SIGNATURE, tampered, SECRETS))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("signature mismatch");
    }

    @Test
    @DisplayName("Rejects a signature made with another secret")
    void rejectsWrongSecret() {
        assertThatThrownBy(() -> verifierAt(quetzalTime(QZ_SUCCEEDED_SIGNATURE))
                .verify(QZ_SUCCEEDED_SIGNATURE, sample(QZ_SUCCEEDED), List.of("another_secret")))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    @DisplayName("Secret rotation: accepts a header with an old and a new v1 when one matches")
    void acceptsAnyOfSeveralSignatures() {
        String valid = QZ_SUCCEEDED_SIGNATURE.substring(QZ_SUCCEEDED_SIGNATURE.indexOf("v1="));
        String header = "t=1795888803,v1=" + "0".repeat(64) + "," + valid;
        assertThatCode(() -> verifierAt(quetzalTime(QZ_SUCCEEDED_SIGNATURE))
                .verify(header, sample(QZ_SUCCEEDED), SECRETS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Secret rotation: accepts a signature made with either of two active secrets")
    void acceptsEitherActiveSecret() {
        assertThatCode(() -> verifierAt(quetzalTime(QZ_SUCCEEDED_SIGNATURE))
                .verify(QZ_SUCCEEDED_SIGNATURE, sample(QZ_SUCCEEDED), List.of("new_secret", QUETZAL_SECRET)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "now = t {0} s")
    @ValueSource(longs = {301, -301, 86_400})
    @DisplayName("Rejects a valid signature whose timestamp is more than 300 s away (replay protection)")
    void rejectsTimestampOutsideWindow(long offsetSeconds) {
        Instant now = quetzalTime(QZ_SUCCEEDED_SIGNATURE).plusSeconds(offsetSeconds);
        assertThatThrownBy(() -> verifierAt(now).verify(QZ_SUCCEEDED_SIGNATURE, sample(QZ_SUCCEEDED), SECRETS))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("timestamp outside the allowed window");
    }

    @Test
    @DisplayName("Accepts a timestamp exactly at the edge of the window")
    void acceptsTimestampAtEdgeOfWindow() {
        Instant now = quetzalTime(QZ_SUCCEEDED_SIGNATURE).plusSeconds(300);
        assertThatCode(() -> verifierAt(now).verify(QZ_SUCCEEDED_SIGNATURE, sample(QZ_SUCCEEDED), SECRETS))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "v1=abc", "t=1795888803", "t=abc,v1=abc", "garbage"})
    @DisplayName("Rejects a missing or malformed header")
    void rejectsMalformedHeader(String header) {
        assertThatThrownBy(() -> verifierAt(quetzalTime(QZ_SUCCEEDED_SIGNATURE))
                .verify(header, sample(QZ_SUCCEEDED), SECRETS))
                .isInstanceOf(InvalidSignatureException.class);
    }
}
