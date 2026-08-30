package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
class MatchOutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 128)
    private String routingKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected MatchOutboxEvent() {}

    static MatchOutboxEvent pending(
            UUID id, String eventType, String routingKey, String payload, Instant createdAt) {
        MatchOutboxEvent event = new MatchOutboxEvent();
        event.id = id;
        event.eventType = eventType;
        event.routingKey = routingKey;
        event.payload = payload;
        event.createdAt = createdAt;
        return event;
    }

    void markPublished(Instant now) {
        if (publishedAt == null) publishedAt = now;
    }

    UUID id() { return id; }
    String eventType() { return eventType; }
    String routingKey() { return routingKey; }
    String payload() { return payload; }
}
