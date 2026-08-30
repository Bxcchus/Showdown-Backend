package lol.pinkward.showdown.match;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "duel_challenges")
class DuelChallenge {
    @Id private UUID id;
    @Column(name = "challenger_id", nullable = false) private UUID challengerId;
    @Column(name = "opponent_id", nullable = false) private UUID opponentId;
    @Column(name = "challenger_riot_id", nullable = false, length = 22) private String challengerRiotId;
    @Column(name = "opponent_riot_id", nullable = false, length = 22) private String opponentRiotId;
    @Column(nullable = false, length = 16) private String region;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private DuelChallengeStatus status;
    @Column(name = "match_id") private UUID matchId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "responded_at") private Instant respondedAt;
    @Version @Column(nullable = false) private long version;
    protected DuelChallenge() {}

    static DuelChallenge pending(UUID challengerId, UUID opponentId, String challengerRiotId,
            String opponentRiotId, String region, Instant now, Instant expiresAt) {
        DuelChallenge value = new DuelChallenge();
        value.id = UUID.randomUUID(); value.challengerId = challengerId; value.opponentId = opponentId;
        value.challengerRiotId = challengerRiotId; value.opponentRiotId = opponentRiotId;
        value.region = region; value.status = DuelChallengeStatus.PENDING;
        value.createdAt = now; value.expiresAt = expiresAt;
        return value;
    }

    void accept(UUID matchId, Instant now) { status = DuelChallengeStatus.ACCEPTED; this.matchId = matchId; respondedAt = now; }
    void complete(Instant now) { if (status == DuelChallengeStatus.ACCEPTED) { status = DuelChallengeStatus.COMPLETED; respondedAt = now; } }
    void decline(Instant now) { status = DuelChallengeStatus.DECLINED; respondedAt = now; }
    void cancel(Instant now) { status = DuelChallengeStatus.CANCELLED; respondedAt = now; }
    void expire(Instant now) { if (status == DuelChallengeStatus.PENDING) { status = DuelChallengeStatus.EXPIRED; respondedAt = now; } }
    boolean involves(UUID playerId) { return challengerId.equals(playerId) || opponentId.equals(playerId); }
    UUID id() { return id; } UUID challengerId() { return challengerId; } UUID opponentId() { return opponentId; }
    String challengerRiotId() { return challengerRiotId; } String opponentRiotId() { return opponentRiotId; }
    String region() { return region; } DuelChallengeStatus status() { return status; } UUID matchId() { return matchId; }
    Instant createdAt() { return createdAt; } Instant expiresAt() { return expiresAt; } Instant respondedAt() { return respondedAt; }
}
