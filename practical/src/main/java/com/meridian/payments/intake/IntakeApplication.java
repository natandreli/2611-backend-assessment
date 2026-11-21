package com.meridian.payments.intake;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Intake of premium payment notifications: verifies each provider webhook, translates it into the
 * canonical event, records it and publishes it. Writing to Salesforce is done by another service
 * (design only, see docs/design/SOLUTION_DESIGN.md).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntakeApplication.class, args);
    }

    /** Injected everywhere time matters (signature windows, receivedAt), so tests can fix it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
