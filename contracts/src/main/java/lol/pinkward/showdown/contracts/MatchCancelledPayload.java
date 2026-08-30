package lol.pinkward.showdown.contracts;

import java.util.List;
import java.util.UUID;

public record MatchCancelledPayload(
        UUID matchId,
        UUID reservationId,
        String reason,
        List<UUID> requeuePlayerIds) {

    public MatchCancelledPayload {
        requeuePlayerIds = List.copyOf(requeuePlayerIds);
        if (matchId == null || reservationId == null || reason == null || reason.isBlank()
                || requeuePlayerIds.size() > 10
                || requeuePlayerIds.stream().anyMatch(java.util.Objects::isNull)
                || requeuePlayerIds.stream().distinct().count() != requeuePlayerIds.size()) {
            throw new IllegalArgumentException("A match-cancelled payload requires distinct requeue players");
        }
    }
}
