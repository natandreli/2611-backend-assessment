package com.meridian.payments.intake.provider.quetzal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.meridian.payments.intake.provider.Hmac;
import com.meridian.payments.intake.provider.ProviderAdapter.InvalidSignatureException;

/**
 * Quetzal signature: {@code Quetzal-Signature: t=<unix_timestamp>,v1=<signature>}, where the
 * signature is the lowercase hex HMAC-SHA256 of {@code <t>.<raw_body>}. During a secret rotation
 * the header may carry several {@code v1} values; the request is valid if any of them matches.
 */
public class QuetzalSignatureVerifier {

    private static final HexFormat HEX = HexFormat.of();

    private final Clock clock;
    private final Duration tolerance;

    public QuetzalSignatureVerifier(Clock clock, Duration tolerance) {
        this.clock = clock;
        this.tolerance = tolerance;
    }

    public void verify(String header, byte[] rawBody, List<String> secrets) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("missing signature header");
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] keyValue = part.trim().split("=", 2);
            if (keyValue.length != 2) {
                throw new InvalidSignatureException("malformed signature header");
            }
            switch (keyValue[0]) {
                case "t" -> timestamp = keyValue[1];
                case "v1" -> signatures.add(keyValue[1]);
                default -> { } // other signature schemes are ignored
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException("malformed signature header");
        }
        long t;
        try {
            t = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new InvalidSignatureException("malformed signature timestamp");
        }
        long skewSeconds = Math.abs(clock.instant().getEpochSecond() - t);
        if (skewSeconds > tolerance.toSeconds()) {
            throw new InvalidSignatureException("timestamp outside the allowed window");
        }
        for (String secret : secrets) {
            byte[] expected = HEX.formatHex(Hmac.sha256(secret, timestamp + ".", rawBody))
                    .getBytes(StandardCharsets.US_ASCII);
            for (String signature : signatures) {
                if (Hmac.constantTimeEquals(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
                    return;
                }
            }
        }
        throw new InvalidSignatureException("signature mismatch");
    }
}
