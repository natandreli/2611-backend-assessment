package com.meridian.payments.intake.support;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.meridian.payments.intake.config.IntakeProperties;
import com.meridian.payments.intake.config.IntakeProperties.ProviderSettings;
import com.meridian.payments.intake.event.CanonicalEventValidator;
import com.meridian.payments.intake.port.SecretProvider;
import com.meridian.payments.intake.provider.andina.AndinaAdapter;
import com.meridian.payments.intake.provider.quetzal.QuetzalAdapter;

/** Builds the adapters without Spring, with the same configuration as application.yml. */
public final class Adapters {

    public static final IntakeProperties PROPERTIES = new IntakeProperties(Map.of(
            "quetzal", new ProviderSettings("MX", Duration.ofSeconds(300), "quetzal-webhook-secret"),
            "andina", new ProviderSettings("CO", Duration.ofMinutes(10), "andina-webhook-secret")));

    private static final SecretProvider SECRETS = name -> switch (name) {
        case "quetzal-webhook-secret" -> List.of(TestData.QUETZAL_SECRET);
        case "andina-webhook-secret" -> List.of(TestData.ANDINA_SECRET);
        default -> List.of();
    };

    private static final CanonicalEventValidator VALIDATOR = new CanonicalEventValidator();

    private Adapters() {
    }

    public static QuetzalAdapter quetzal(Clock clock) {
        return new QuetzalAdapter(PROPERTIES, SECRETS, clock, VALIDATOR);
    }

    public static AndinaAdapter andina(Clock clock) {
        return new AndinaAdapter(PROPERTIES, SECRETS, clock, VALIDATOR);
    }
}
