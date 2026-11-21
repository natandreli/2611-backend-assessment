package com.meridian.payments.intake.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

import com.meridian.payments.intake.provider.Hmac;

/**
 * Provider samples and official test vectors from reference/providers. The samples are copied to
 * the test classpath byte for byte (see pom.xml), so the official signatures match.
 * The secrets are the providers' fictional sandbox secrets published in their specifications.
 */
public final class TestData {

    public static final String QUETZAL_SECRET = "qz_test_whsec_4f8a1c2e9b7d";
    public static final String ANDINA_SECRET = "andina_test_secret_7c3e19ab";

    // Quetzal test vectors (reference/providers/quetzal-mx/WEBHOOK_SPEC.md)
    public static final String QZ_SUCCEEDED = "quetzal-mx/samples/01-charge-succeeded.json";
    public static final String QZ_SUCCEEDED_SIGNATURE =
            "t=1795888803,v1=2d07770582c02f15235ac4e027b7732beef86be103c574a1c3b988a4275f5031";
    public static final String QZ_FAILED = "quetzal-mx/samples/02-charge-failed.json";
    public static final String QZ_FAILED_SIGNATURE =
            "t=1795889403,v1=d793d3da0a8d4d66e984ada3daa8b9f473a0eef153d99cdd83bb70f60f13ccc9";
    public static final String QZ_REFUNDED = "quetzal-mx/samples/03-charge-refunded.json";
    public static final String QZ_REFUNDED_SIGNATURE =
            "t=1795975203,v1=a61f9d88d861cd4cc63359b5ca6e6d11d55369db7079387dee9a5024ccb2cae6";

    // Andina test vectors (reference/providers/andina-co/WEBHOOK_SPEC.md)
    public static final String AND_PENDING = "andina-co/samples/01-transaccion-pendiente.json";
    public static final String AND_PENDING_TIMESTAMP = "2026-11-28T14:02:59-05:00";
    public static final String AND_PENDING_SIGNATURE = "JhaBZfk7eQsoMbbQbThmAx/bCyehBOzKYw8XTP2a40k=";
    public static final String AND_APPROVED = "andina-co/samples/02-transaccion-aprobada.json";
    public static final String AND_APPROVED_TIMESTAMP = "2026-11-28T14:03:11-05:00";
    public static final String AND_APPROVED_SIGNATURE = "MFQjfviySi6CjqbWtQcy5spKtXxHIxAvmg5W3PTzPJk=";
    public static final String AND_APPROVED_RETRY = "andina-co/samples/03-transaccion-aprobada-reintento.json";
    public static final String AND_APPROVED_RETRY_TIMESTAMP = "2026-11-28T14:08:11-05:00";
    public static final String AND_APPROVED_RETRY_SIGNATURE = "CNu6m5X6B9bMKTsi/IzZfGquIh/KdxTyesBtL4ax424=";
    public static final String AND_REVERSED = "andina-co/samples/04-transaccion-reversada.json";
    public static final String AND_REVERSED_TIMESTAMP = "2026-12-03T09:15:42-05:00";
    public static final String AND_REVERSED_SIGNATURE = "AfGO+MEffk0AWIEEE3cgyff4hWewoOaDhygCWxs/fNI=";

    private TestData() {
    }

    /** Raw bytes of a sample, exactly as in reference/providers. */
    public static byte[] sample(String path) {
        try (InputStream in = TestData.class.getResourceAsStream("/providers/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("Sample not found: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sampleText(String path) {
        return new String(sample(path), StandardCharsets.UTF_8);
    }

    public static Instant quetzalTime(String signatureHeader) {
        String t = signatureHeader.substring(2, signatureHeader.indexOf(','));
        return Instant.ofEpochSecond(Long.parseLong(t));
    }

    public static Instant andinaTime(String timestampHeader) {
        return OffsetDateTime.parse(timestampHeader).toInstant();
    }

    /** Signs a modified body the way Quetzal does, for cases without an official vector. */
    public static String signQuetzal(String secret, long t, byte[] body) {
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(Hmac.sha256(secret, t + ".", body));
    }

    /** Signs a modified body the way Andina does, for cases without an official vector. */
    public static String signAndina(String secret, String timestamp, byte[] body) {
        return Base64.getEncoder().encodeToString(Hmac.sha256(secret, timestamp + ".", body));
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
