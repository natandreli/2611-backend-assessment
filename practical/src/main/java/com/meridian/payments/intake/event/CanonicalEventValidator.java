package com.meridian.payments.intake.event;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.payments.intake.config.Json;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Validates each canonical event against the published contract before it is published, so an
 * event that breaks the contract, or carries a field that is not in it (for example personal
 * data), never reaches the topic. The schema file is copied from docs/api/events at build time.
 */
@Component
public class CanonicalEventValidator {

    static final String SCHEMA_RESOURCE = "/schemas/premium-payment.schema.json";

    private final JsonSchema schema;

    public CanonicalEventValidator() {
        try (InputStream in = CanonicalEventValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Schema not found on the classpath: " + SCHEMA_RESOURCE);
            }
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                    .formatAssertionsEnabled(true)
                    .build();
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the contract violations of the event; empty when it is valid. */
    public List<String> validate(CanonicalEvent event) {
        JsonNode json = Json.mapper().valueToTree(event);
        return schema.validate(json).stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
    }
}
