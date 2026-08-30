package lol.pinkward.showdown.matchmaking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_events")
class MatchmakingInboxEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected MatchmakingInboxEvent() {}

    static MatchmakingInboxEvent received(UUID eventId, Instant now) {
        MatchmakingInboxEvent event = new MatchmakingInboxEvent();
        event.eventId = eventId;
        event.receivedAt = now;
        return event;
    }
}
