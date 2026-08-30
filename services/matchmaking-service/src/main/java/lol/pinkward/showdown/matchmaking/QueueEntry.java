package lol.pinkward.showdown.matchmaking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "queue_entries")
class QueueEntry {

    static final String LOCAL_BOT_PREFIX = "local-bot:";

    @Id
    private UUID id;

    @Column(name = "player_id", nullable = false, unique = true)
    private UUID playerId;

    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "local_bot", nullable = false)
    private boolean localBot;

    @Column(nullable = false, length = 16)
    private String region;

    @Column(nullable = false, length = 24)
    private String mode;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "primary_role", nullable = false, length = 16)
    private String primaryRole;

    @Column(name = "secondary_role", nullable = false, length = 16)
    private String secondaryRole;

    @Column(nullable = false)
    private int mmr;

    @Column(name = "skill_mean", nullable = false)
    private double skillMean;

    @Column(name = "skill_deviation", nullable = false)
    private double skillDeviation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QueueStatus status;

    @Column(name = "reservation_id")
    private UUID reservationId;

    protected QueueEntry() {}

    static QueueEntry join(UUID playerId, String region, String idempotencyKey, Instant joinedAt) {
        return join(playerId, region, idempotencyKey, joinedAt, "MID", "JUNGLE");
    }

    static QueueEntry join(
            UUID playerId,
            String region,
            String idempotencyKey,
            Instant joinedAt,
            String primaryRole,
            String secondaryRole) {
        return join(playerId, null, region, idempotencyKey, joinedAt, primaryRole, secondaryRole, false);
    }

    static QueueEntry join(
            UUID playerId,
            UUID partyId,
            String region,
            String idempotencyKey,
            Instant joinedAt,
            String primaryRole,
            String secondaryRole,
            boolean localBot) {
        return join(playerId, partyId, region, idempotencyKey, joinedAt,
                primaryRole, secondaryRole, localBot,
                PlayerRatingSnapshot.INITIAL_RATING,
                PlayerRatingSnapshot.INITIAL_SKILL_MEAN,
                PlayerRatingSnapshot.INITIAL_SKILL_DEVIATION);
    }

    static QueueEntry join(
            UUID playerId,
            String region,
            String idempotencyKey,
            Instant joinedAt,
            String primaryRole,
            String secondaryRole,
            boolean localBot,
            int mmr) {
        return join(playerId, null, region, idempotencyKey, joinedAt,
                primaryRole, secondaryRole, localBot, mmr,
                meanFromLegacyMmr(mmr), PlayerRatingSnapshot.INITIAL_SKILL_DEVIATION);
    }

    static QueueEntry joinSolo(
            UUID playerId,
            String region,
            String mode,
            String idempotencyKey,
            Instant joinedAt,
            String primaryRole,
            String secondaryRole,
            boolean localBot,
            int mmr,
            double skillMean,
            double skillDeviation) {
        return create(playerId, null, region, mode, idempotencyKey, joinedAt,
                primaryRole, secondaryRole, localBot, mmr, skillMean, skillDeviation);
    }

    static QueueEntry join(
            UUID playerId,
            UUID partyId,
            String region,
            String idempotencyKey,
            Instant joinedAt,
            String primaryRole,
            String secondaryRole,
            boolean localBot,
            int mmr) {
        return join(playerId, partyId, region, idempotencyKey, joinedAt,
                primaryRole, secondaryRole, localBot, mmr,
                meanFromLegacyMmr(mmr), PlayerRatingSnapshot.INITIAL_SKILL_DEVIATION);
    }

    static QueueEntry join(
            UUID playerId,
            UUID partyId,
            String region,
            String idempotencyKey,
            Instant joinedAt,
            String primaryRole,
            String secondaryRole,
            boolean localBot,
            int mmr,
            double skillMean,
            double skillDeviation) {
        return create(playerId, partyId, region, "FIVE_V_FIVE", idempotencyKey, joinedAt,
                primaryRole, secondaryRole, localBot, mmr, skillMean, skillDeviation);
    }

    private static QueueEntry create(
            UUID playerId,
            UUID partyId,
            String region,
            String mode,
            String idempotencyKey,
            Instant joinedAt,
            String primaryRole,
            String secondaryRole,
            boolean localBot,
            int mmr,
            double skillMean,
            double skillDeviation) {
        QueueEntry entry = new QueueEntry();
        entry.id = UUID.randomUUID();
        entry.playerId = playerId;
        entry.partyId = partyId;
        entry.localBot = localBot;
        entry.region = region;
        entry.mode = mode;
        entry.idempotencyKey = idempotencyKey;
        entry.joinedAt = joinedAt;
        entry.primaryRole = primaryRole;
        entry.secondaryRole = secondaryRole;
        entry.mmr = Math.max(0, mmr);
        entry.skillMean = skillMean;
        entry.skillDeviation = skillDeviation;
        entry.status = QueueStatus.QUEUED;
        return entry;
    }

    void reserve(UUID reservationId) {
        if (status != QueueStatus.QUEUED) {
            throw new IllegalStateException("Only queued entries can be reserved");
        }
        this.status = QueueStatus.RESERVED;
        this.reservationId = reservationId;
    }

    void requeue(Instant now) {
        if (status == QueueStatus.RESERVED) {
            status = QueueStatus.QUEUED;
            reservationId = null;
            joinedAt = now;
        }
    }

    boolean isLocalBot() {
        return localBot || idempotencyKey.startsWith(LOCAL_BOT_PREFIX);
    }

    void updateSkill(int rating, double mean, double deviation) {
        if (status == QueueStatus.QUEUED) {
            mmr = Math.max(0, rating);
            skillMean = mean;
            skillDeviation = deviation;
        }
    }

    private static double meanFromLegacyMmr(int mmr) {
        return PlayerRatingSnapshot.INITIAL_SKILL_MEAN + (mmr - PlayerRatingSnapshot.INITIAL_RATING) / 40.0;
    }

    UUID id() { return id; }
    UUID playerId() { return playerId; }
    UUID partyId() { return partyId; }
    String region() { return region; }
    String mode() { return mode; }
    String idempotencyKey() { return idempotencyKey; }
    Instant joinedAt() { return joinedAt; }
    String primaryRole() { return primaryRole; }
    String secondaryRole() { return secondaryRole; }
    int mmr() { return mmr; }
    double skillMean() { return skillMean; }
    double skillDeviation() { return skillDeviation; }
    QueueStatus status() { return status; }
    UUID reservationId() { return reservationId; }
}
