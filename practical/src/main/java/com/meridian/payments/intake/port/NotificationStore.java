package com.meridian.payments.intake.port;

/**
 * The {@code notification} table of the integration store (SOLUTION_DESIGN.md, section 7).
 * Production: Azure SQL Database with a managed identity. Here: {@code stub.InMemoryNotificationStore}.
 * Every method throws {@link StoreUnavailableException} when the database cannot be reached.
 */
public interface NotificationStore {

    void insert(NotificationRecord notification);

    /** True if a notification with this event ID was already recorded (index on {@code event_id}). */
    boolean existsByEventId(String eventId);

    /** Marks a recorded notification as FAILED, used when it could not be published. */
    void markFailed(String correlationId, String reason);

    class StoreUnavailableException extends RuntimeException {
        public StoreUnavailableException(String message) {
            super(message);
        }
    }
}
