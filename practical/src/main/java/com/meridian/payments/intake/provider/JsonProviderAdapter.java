package com.meridian.payments.intake.provider;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.payments.intake.config.Json;
import com.meridian.payments.intake.event.CanonicalEventValidator;
import com.meridian.payments.intake.provider.MappingResult.Mapped;
import com.meridian.payments.intake.provider.MappingResult.Parked;

/**
 * Shared mapping flow for providers that send JSON: parse, translate, validate against the event
 * contract, and park anything that cannot be translated. Subclasses only contain the
 * provider-specific rules.
 */
public abstract class JsonProviderAdapter implements ProviderAdapter {

    private static final int MAX_REASON_LENGTH = 1000;
    private static final int MAX_IDENTIFIER_LENGTH = 80;

    private final CanonicalEventValidator validator;

    protected JsonProviderAdapter(CanonicalEventValidator validator) {
        this.validator = validator;
    }

    @Override
    public final MappingResult map(byte[] rawBody, NotificationContext context) {
        JsonNode root;
        try {
            root = Json.mapper().readTree(rawBody);
        } catch (IOException e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            return new Parked("body is not a valid JSON object", null, null, null);
        }
        try {
            Mapped mapped = translate(root, context);
            List<String> violations = validator.validate(mapped.event());
            if (!violations.isEmpty()) {
                throw new ParkException("canonical event does not match the contract: " + String.join("; ", violations));
            }
            return mapped;
        } catch (ParkException e) {
            return parked(root, e.getMessage());
        } catch (RuntimeException e) {
            // A bug or an unexpected payload shape must not lose an authentic notification.
            return parked(root, "unexpected mapping error: " + e.getClass().getSimpleName());
        }
    }

    /** Provider-specific translation. Throws {@link ParkException} when the payload cannot be mapped. */
    protected abstract Mapped translate(JsonNode root, NotificationContext context);

    /** Provider ID of the notification, or null if it cannot be read. */
    protected abstract String providerEventId(JsonNode root);

    /** Provider transaction ID, or null if it cannot be read. */
    protected abstract String providerTransactionId(JsonNode root);

    /** Removes every field with payer personal data or payment tokens from a copy of the payload. */
    protected abstract void removePersonalData(ObjectNode copy);

    private Parked parked(JsonNode root, String reason) {
        ObjectNode copy = root.deepCopy();
        removePersonalData(copy);
        String payload;
        try {
            payload = Json.mapper().writeValueAsString(copy);
        } catch (JsonProcessingException e) {
            payload = null;
        }
        return new Parked(truncate(reason), identifier(() -> providerEventId(root)),
                identifier(() -> providerTransactionId(root)), payload);
    }

    private static String truncate(String reason) {
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }

    /**
     * An identifier read from a payload that could not be mapped, so nothing has checked it yet.
     * One longer than its column (80, as in Salesforce) is left empty: storing it would fail on every
     * provider retry. The full payload is still kept for Operations.
     */
    private static String identifier(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value != null && value.length() <= MAX_IDENTIFIER_LENGTH ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- Helpers for subclasses ----

    /** Required, non-blank text field. */
    protected static String requiredText(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new ParkException("missing or invalid field: " + field);
        }
        return node.asText();
    }

    /** Optional text field: null when absent or not text. */
    protected static String optionalText(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /** Required integer field. */
    protected static long requiredLong(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new ParkException("missing or invalid field: " + field);
        }
        return node.asLong();
    }
}
