package com.meridian.payments.intake.provider;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 helpers shared by the signature verifiers. */
public final class Hmac {

    private static final String ALGORITHM = "HmacSHA256";

    private Hmac() {
    }

    /** HMAC-SHA256 of {@code prefix} followed by the raw body bytes, without copying or re-encoding the body. */
    public static byte[] sha256(String secret, String prefix, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            mac.update(rawBody);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /** Constant-time comparison, so the time taken does not reveal how many bytes matched. */
    public static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
