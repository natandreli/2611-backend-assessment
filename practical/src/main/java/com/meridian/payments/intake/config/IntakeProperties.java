package com.meridian.payments.intake.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-provider configuration (NFR9): each adapter has its own country, timestamp tolerance and
 * secret name. The key is the provider's path segment ({@code quetzal}, {@code andina}).
 */
@ConfigurationProperties("intake")
public record IntakeProperties(Map<String, ProviderSettings> providers) {

    public record ProviderSettings(String country, Duration timestampTolerance, String secretName) {
    }

    public ProviderSettings provider(String pathSegment) {
        ProviderSettings settings = providers == null ? null : providers.get(pathSegment);
        if (settings == null) {
            throw new IllegalStateException("Missing configuration intake.providers." + pathSegment);
        }
        return settings;
    }
}
