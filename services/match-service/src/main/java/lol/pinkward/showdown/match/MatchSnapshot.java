package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record MatchSnapshot(
        UUID matchId,
        UUID reservationId,
        String region,
        String mode,
        String status,
        Instant createdAt,
        Instant readyDeadline,
        String lobbyName,
        String lobbyPassword,
        String winningTeam,
        Instant completedAt,
        List<MatchPlayerSnapshot> players) {

    static MatchSnapshot from(GameMatch match, List<MatchPlayer> players, String lobbyPassword) {
        return new MatchSnapshot(
                match.id(),
                match.reservationId(),
                match.region(),
                match.mode(),
                match.status().name(),
                match.createdAt(),
                match.readyDeadline(),
                match.status() == MatchStatus.CONFIRMED ? match.lobbyName() : null,
                match.status() == MatchStatus.CONFIRMED ? lobbyPassword : null,
                match.winningTeam() == null ? null : match.winningTeam().name(),
                match.completedAt(),
                players.stream().map(MatchPlayerSnapshot::from).toList());
    }
}
