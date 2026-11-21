package com.meridian.payments.intake.stub;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.meridian.payments.intake.port.EventPublisher;
import com.meridian.payments.intake.port.OutgoingMessage;

/**
 * Stub of the Service Bus topic. Like Service Bus with duplicate detection, it silently discards
 * a message whose MessageId was already accepted. It can be made unavailable to test the 503 path.
 */
@Component
public class InMemoryEventPublisher implements EventPublisher {

    private final List<OutgoingMessage> accepted = new ArrayList<>();
    private final Set<String> seenMessageIds = new HashSet<>();
    private int discardedDuplicates;
    private boolean available = true;

    @Override
    public synchronized void publish(OutgoingMessage message) {
        if (!available) {
            throw new PublishException("Service Bus unavailable (stub)");
        }
        if (seenMessageIds.add(message.messageId())) {
            accepted.add(message);
        } else {
            discardedDuplicates++;
        }
    }

    /** Messages stored in the topic, duplicates excluded. */
    public synchronized List<OutgoingMessage> messages() {
        return List.copyOf(accepted);
    }

    public synchronized int discardedDuplicates() {
        return discardedDuplicates;
    }

    public synchronized void setAvailable(boolean available) {
        this.available = available;
    }

    public synchronized void reset() {
        accepted.clear();
        seenMessageIds.clear();
        discardedDuplicates = 0;
        available = true;
    }
}
