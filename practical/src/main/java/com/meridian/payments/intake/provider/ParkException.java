package com.meridian.payments.intake.provider;

/**
 * Thrown while translating a notification that cannot be mapped. The message becomes the
 * {@code reason} of the parked notification, so it must never contain payer personal data.
 */
public class ParkException extends RuntimeException {

    public ParkException(String reason) {
        super(reason);
    }
}
