package lol.pinkward.showdown.matchmaking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "player_rating_snapshots")
class PlayerRatingSnapshot {

    static final int INITIAL_RATING = 1200;
    static final double INITIAL_SKILL_MEAN = 25.0;
    static final double INITIAL_SKILL_DEVIATION = INITIAL_SKILL_MEAN / 3.0;

    @Id
    @Column(name = "player_id")
    private UUID playerId;

    @Column(nullable = false)
    private int rating;

    @Column(name = "peak_rating", nullable = false)
    private int peakRating;

    @Column(nullable = false)
    private int games;

    @Column(name = "skill_mean", nullable = false)
    private double skillMean;

    @Column(name = "skill_deviation", nullable = false)
    private double skillDeviation;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PlayerRatingSnapshot() {}

    static PlayerRatingSnapshot create(
            UUID playerId,
            int rating,
            int peakRating,
            int games,
            double skillMean,
            double skillDeviation,
            Instant now) {
        PlayerRatingSnapshot snapshot = new PlayerRatingSnapshot();
        snapshot.playerId = playerId;
        snapshot.updateIfNewer(rating, peakRating, games, skillMean, skillDeviation, now);
        return snapshot;
    }

    boolean updateIfNewer(
            int rating,
            int peakRating,
            int games,
            double skillMean,
            double skillDeviation,
            Instant now) {
        if (this.games > games) return false;
        this.rating = rating;
        this.peakRating = peakRating;
        this.games = games;
        this.skillMean = skillMean;
        this.skillDeviation = skillDeviation;
        this.updatedAt = now;
        return true;
    }

    int rating() { return rating; }
    int games() { return games; }
    double skillMean() { return skillMean; }
    double skillDeviation() { return skillDeviation; }
}
