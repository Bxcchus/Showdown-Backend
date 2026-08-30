package lol.pinkward.showdown.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "riot_link_challenges")
class RiotLinkChallenge {

    @Id
    @Column(name = "challenge_id")
    private UUID challengeId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected RiotLinkChallenge() {}

    static RiotLinkChallenge issue(UUID playerId, Instant now, Instant expiresAt) {
        RiotLinkChallenge challenge = new RiotLinkChallenge();
        challenge.challengeId = UUID.randomUUID();
        challenge.playerId = playerId;
        challenge.issuedAt = now;
        challenge.expiresAt = expiresAt;
        return challenge;
    }

    boolean active(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    void consume(Instant now) {
        consumedAt = now;
    }

    UUID challengeId() { return challengeId; }
    UUID playerId() { return playerId; }
    Instant expiresAt() { return expiresAt; }
}
