package lol.pinkward.showdown.match;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "duel_watcher_tokens")
class DuelWatcherToken {
    @Id private UUID id;
    @Column(name = "match_id", nullable = false) private UUID matchId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Column(nullable = false, length = 8) private String role;
    @Column(nullable = false, length = 32) private String state;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Version @Column(nullable = false) private long version;
    protected DuelWatcherToken() {}

    static DuelWatcherToken issue(UUID matchId, UUID playerId, String tokenHash, String role,
            Instant now, Instant expiresAt) {
        DuelWatcherToken value = new DuelWatcherToken();
        value.id = UUID.randomUUID(); value.matchId = matchId; value.playerId = playerId;
        value.tokenHash = tokenHash; value.role = role; value.state = "ISSUED";
        value.createdAt = now; value.expiresAt = expiresAt;
        return value;
    }

    void rotate(String tokenHash, Instant now, Instant expiresAt) {
        this.tokenHash = tokenHash; this.state = "ISSUED"; this.createdAt = now;
        this.expiresAt = expiresAt; this.lastSeenAt = null; this.revokedAt = null;
    }
    void touch(String state, Instant now) { this.state = state; this.lastSeenAt = now; }
    void revoke(Instant now) { this.revokedAt = now; this.state = "REVOKED"; }
    boolean active(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
    UUID matchId() { return matchId; } UUID playerId() { return playerId; }
    String role() { return role; } String state() { return state; }
    Instant expiresAt() { return expiresAt; } Instant lastSeenAt() { return lastSeenAt; }
}
