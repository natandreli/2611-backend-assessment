package com.meridian.payments.intake.event;

/** Canonical payment status (SOLUTION_DESIGN.md, section 3). */
public enum PaymentStatus {
    PENDING,
    CAPTURED,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED
}
