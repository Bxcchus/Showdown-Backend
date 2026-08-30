package lol.pinkward.showdown.matchmaking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lol.pinkward.showdown.contracts.DuelRatingUpdatedPayload;

@Entity
@Table(name = "duel_rating_snapshots")
class DuelRatingSnapshot {

    static final int INITIAL_MMR = 1500;
    static final double INITIAL_RATING_DEVIATION = 350.0;

    @Id
    @Column(name = "player_id")
    private UUID playerId;

    @Column(nullable = false)
    private int mmr;

    @Column(name = "peak_mmr", nullable = false)
    private int peakMmr;

    @Column(nullable = false)
    private int games;

    @Column(name = "season_code", nullable = false, length = 16)
    private String seasonCode;

    @Column(nullable = false, length = 16)
    private String region;

    @Column(nullable = false)
    private double rating;

    @Column(name = "rating_deviation", nullable = false)
    private double ratingDeviation;

    @Column(nullable = false)
    private double volatility;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected DuelRatingSnapshot() {}

    static DuelRatingSnapshot create(DuelRatingUpdatedPayload payload, Instant occurredAt) {
        DuelRatingSnapshot snapshot = new DuelRatingSnapshot();
        snapshot.playerId = payload.playerId();
        snapshot.apply(payload, occurredAt);
        return snapshot;
    }

    boolean updateIfNewer(DuelRatingUpdatedPayload payload, Instant occurredAt) {
        if (updatedAt != null && !occurredAt.isAfter(updatedAt)) return false;
        if (seasonCode != null && seasonCode.equals(payload.season()) && games > payload.games()) return false;
        apply(payload, occurredAt);
        return true;
    }

    private void apply(DuelRatingUpdatedPayload payload, Instant occurredAt) {
        mmr = payload.mmr();
        peakMmr = payload.peakMmr();
        games = payload.games();
        seasonCode = payload.season();
        region = payload.region();
        rating = payload.rating();
        ratingDeviation = payload.ratingDeviation();
        volatility = payload.volatility();
        updatedAt = occurredAt;
    }

    int mmr() { return mmr; }
    int games() { return games; }
    String seasonCode() { return seasonCode; }
    String region() { return region; }
    double queueMean() { return 25.0 + (rating - INITIAL_MMR) / 40.0; }
    double queueDeviation() { return Math.max(0.1, ratingDeviation / 40.0); }
}
