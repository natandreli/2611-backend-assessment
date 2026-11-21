package com.meridian.payments.intake.stub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.meridian.payments.intake.port.NotificationRecord;
import com.meridian.payments.intake.port.NotificationStore;

/**
 * Stub of the {@code notification} table, keyed by correlation ID (its primary key). It can be
 * made unavailable to test the 503 path.
 */
@Component
public class InMemoryNotificationStore implements NotificationStore {

    private final Map<String, NotificationRecord> rows = new LinkedHashMap<>();
    private boolean available = true;

    @Override
    public synchronized void insert(NotificationRecord notification) {
        checkAvailable();
        if (rows.putIfAbsent(notification.correlationId(), notification) != null) {
            throw new IllegalStateException("Duplicate primary key (correlation ID)");
        }
    }

    @Override
    public synchronized boolean existsByEventId(String eventId) {
        checkAvailable();
        return rows.values().stream().anyMatch(row -> eventId.equals(row.eventId()));
    }

    @Override
    public synchronized void markFailed(String correlationId, String reason) {
        checkAvailable();
        rows.computeIfPresent(correlationId, (id, row) -> row.withFailure(reason));
    }

    public synchronized List<NotificationRecord> all() {
        return new ArrayList<>(rows.values());
    }

    public synchronized Optional<NotificationRecord> find(String correlationId) {
        return Optional.ofNullable(rows.get(correlationId));
    }

    public synchronized void setAvailable(boolean available) {
        this.available = available;
    }

    public synchronized void reset() {
        rows.clear();
        available = true;
    }

    private void checkAvailable() {
        if (!available) {
            throw new StoreUnavailableException("Integration store unavailable (stub)");
        }
    }
}
