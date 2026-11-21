package com.meridian.payments.intake.provider;

import java.util.Arrays;
import java.util.Optional;

/** Providers with an adapter in this intake. Austral (Chile) is design only. */
public enum Provider {
    QUETZAL("Quetzal", "quetzal"),
    ANDINA("Andina", "andina");

    private final String displayName;
    private final String pathSegment;

    Provider(String displayName, String pathSegment) {
        this.displayName = displayName;
        this.pathSegment = pathSegment;
    }

    /** Value of the canonical {@code provider} field. */
    public String displayName() {
        return displayName;
    }

    /** Segment of the webhook path ({@code /webhooks/{pathSegment}}) and configuration key. */
    public String pathSegment() {
        return pathSegment;
    }

    public static Optional<Provider> fromPathSegment(String segment) {
        return Arrays.stream(values()).filter(p -> p.pathSegment.equals(segment)).findFirst();
    }
}
