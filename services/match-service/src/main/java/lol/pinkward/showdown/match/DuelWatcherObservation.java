package lol.pinkward.showdown.match;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "duel_watcher_observations")
class DuelWatcherObservation {
    @Id private UUID id;
    @Column(name = "match_id", nullable = false) private UUID matchId;
    @Column(name = "reporter_id", nullable = false) private UUID reporterId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private DuelObjective objective;
    @Column(name = "winner_player_id", nullable = false) private UUID winnerPlayerId;
    @Column(name = "observed_at", nullable = false) private Instant observedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected DuelWatcherObservation() {}

    static DuelWatcherObservation observed(UUID matchId, UUID reporterId, DuelObjective objective,
            UUID winnerPlayerId, Instant observedAt, Instant now) {
        DuelWatcherObservation value = new DuelWatcherObservation();
        value.id = UUID.randomUUID(); value.matchId = matchId; value.reporterId = reporterId;
        value.objective = objective; value.winnerPlayerId = winnerPlayerId;
        value.observedAt = observedAt; value.createdAt = now;
        return value;
    }
    UUID winnerPlayerId() { return winnerPlayerId; }
}
