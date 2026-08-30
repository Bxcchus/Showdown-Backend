package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_events")
class InboxEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected InboxEvent() {}

    static InboxEvent received(UUID eventId, Instant now) {
        InboxEvent event = new InboxEvent();
        event.eventId = eventId;
        event.receivedAt = now;
        return event;
    }
}
