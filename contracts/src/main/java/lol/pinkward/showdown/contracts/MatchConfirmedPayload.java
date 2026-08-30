package lol.pinkward.showdown.contracts;

import java.util.List;
import java.util.UUID;

public record MatchConfirmedPayload(UUID matchId, UUID reservationId, List<UUID> playerIds) {

    public MatchConfirmedPayload {
        playerIds = List.copyOf(playerIds);
        int playerCount = playerIds.size();
        if (matchId == null || reservationId == null || (playerCount != 2 && playerCount != 10)
                || playerIds.stream().anyMatch(java.util.Objects::isNull)
                || playerIds.stream().distinct().count() != playerCount) {
            throw new IllegalArgumentException("A confirmed match requires two or ten distinct players");
        }
    }
}
