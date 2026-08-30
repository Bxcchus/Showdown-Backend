package lol.pinkward.showdown.contracts;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        UUID causationId,
        T payload) {

    public EventEnvelope {
        if (eventId == null || eventType == null || eventType.isBlank()
                || eventVersion < 1 || occurredAt == null
                || producer == null || producer.isBlank()
                || correlationId == null || payload == null) {
            throw new IllegalArgumentException("Event envelope fields are required");
        }
    }
}
