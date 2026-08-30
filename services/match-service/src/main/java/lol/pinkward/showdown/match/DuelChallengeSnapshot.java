package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.UUID;

record DuelChallengeSnapshot(UUID challengeId, UUID challengerId, UUID opponentId,
        String region, String status,
        UUID matchId, boolean incoming, boolean host, Instant createdAt, Instant expiresAt, Instant respondedAt) {
    static DuelChallengeSnapshot from(DuelChallenge value, UUID viewer) {
        return new DuelChallengeSnapshot(value.id(), value.challengerId(), value.opponentId(),
                value.region(), value.status().name(),
                value.matchId(), value.opponentId().equals(viewer), value.challengerId().equals(viewer),
                value.createdAt(), value.expiresAt(), value.respondedAt());
    }
}
