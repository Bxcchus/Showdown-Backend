package lol.pinkward.showdown.matchmaking;

import java.time.Instant;
import java.util.UUID;

record QueueSnapshot(
        UUID queueEntryId,
        UUID playerId,
        UUID partyId,
        String region,
        String mode,
        String primaryRole,
        String secondaryRole,
        int mmr,
        String status,
        Instant joinedAt,
        UUID reservationId) {

    static QueueSnapshot from(QueueEntry entry) {
        return new QueueSnapshot(
                entry.id(),
                entry.playerId(),
                entry.partyId(),
                entry.region(),
                entry.mode(),
                entry.primaryRole(),
                entry.secondaryRole(),
                entry.mmr(),
                entry.status().name(),
                entry.joinedAt(),
                entry.reservationId());
    }
}
