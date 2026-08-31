package lol.pinkward.showdown.match;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "team_match_watcher_results")
class TeamMatchWatcherResult {
    @Id private UUID id;
    @Column(name = "match_id", nullable = false) private UUID matchId;
    @Column(name = "reporter_id", nullable = false) private UUID reporterId;
    @Enumerated(EnumType.STRING)
    @Column(name = "reporter_team", nullable = false, length = 8) private TeamSide reporterTeam;
    @Enumerated(EnumType.STRING)
    @Column(name = "winning_team", nullable = false, length = 8) private TeamSide winningTeam;
    @Column(name = "game_id", nullable = false, length = 64) private String gameId;
    @Column(name = "observed_at", nullable = false) private Instant observedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected TeamMatchWatcherResult() {}

    static TeamMatchWatcherResult observed(
            UUID matchId, UUID reporterId, TeamSide reporterTeam, TeamSide winningTeam,
            String gameId, Instant observedAt, Instant now) {
        TeamMatchWatcherResult value = new TeamMatchWatcherResult();
        value.id = UUID.randomUUID();
        value.matchId = matchId;
        value.reporterId = reporterId;
        value.reporterTeam = reporterTeam;
        value.winningTeam = winningTeam;
        value.gameId = gameId;
        value.observedAt = observedAt;
        value.createdAt = now;
        return value;
    }

    TeamSide reporterTeam() { return reporterTeam; }
    TeamSide winningTeam() { return winningTeam; }
    String gameId() { return gameId; }
}
