package com.meridian.payments.intake.provider.andina;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

import com.meridian.payments.intake.provider.Hmac;
import com.meridian.payments.intake.provider.ProviderAdapter.InvalidSignatureException;

/**
 * Andina signature: {@code X-Andina-Signature} is the Base64 HMAC-SHA256 of
 * {@code <X-Andina-Timestamp>.<raw_body>}. The timestamp is ISO-8601 with offset and is used
 * exactly as received in the signed string.
 */
public class AndinaSignatureVerifier {

    private final Clock clock;
    private final Duration tolerance;

    public AndinaSignatureVerifier(Clock clock, Duration tolerance) {
        this.clock = clock;
        this.tolerance = tolerance;
    }

    public void verify(String timestampHeader, String signatureHeader, byte[] rawBody, List<String> secrets) {
        if (timestampHeader == null || timestampHeader.isBlank()
                || signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidSignatureException("missing signature headers");
        }
        OffsetDateTime timestamp;
        try {
            timestamp = OffsetDateTime.parse(timestampHeader);
        } catch (DateTimeParseException e) {
            throw new InvalidSignatureException("malformed signature timestamp");
        }
        Duration skew = Duration.between(timestamp.toInstant(), clock.instant()).abs();
        if (skew.compareTo(tolerance) > 0) {
            throw new InvalidSignatureException("timestamp outside the allowed window");
        }
        byte[] provided;
        try {
            provided = Base64.getDecoder().decode(signatureHeader.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidSignatureException("malformed signature");
        }
        for (String secret : secrets) {
            if (Hmac.constantTimeEquals(Hmac.sha256(secret, timestampHeader + ".", rawBody), provided)) {
                return;
            }
        }
        throw new InvalidSignatureException("signature mismatch");
    }
}
