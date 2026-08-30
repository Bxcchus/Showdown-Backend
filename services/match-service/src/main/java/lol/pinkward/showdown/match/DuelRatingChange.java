package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "duel_rating_changes")
class DuelRatingChange {
    @Id private UUID id;
    @Column(name = "match_id", nullable = false) private UUID matchId;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "previous_mmr", nullable = false) private int previousMmr;
    @Column(name = "previous_peak_rating", nullable = false) private int previousPeakRating;
    @Column(name = "rating_delta", nullable = false) private int ratingDelta;
    @Column(name = "new_mmr", nullable = false) private int newMmr;
    @Column(name = "previous_rating", nullable = false) private double previousRating;
    @Column(name = "previous_deviation", nullable = false) private double previousDeviation;
    @Column(name = "previous_volatility", nullable = false) private double previousVolatility;
    @Column(name = "new_rating", nullable = false) private double newRating;
    @Column(name = "new_deviation", nullable = false) private double newDeviation;
    @Column(name = "new_volatility", nullable = false) private double newVolatility;
    @Column(name = "season_code", nullable = false, length = 16) private String seasonCode;
    @Column(nullable = false, length = 16) private String region;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected DuelRatingChange() {}

    static DuelRatingChange of(UUID matchId, DuelPlayerRating player, int previousMmr, int previousPeak,
            Glicko2Calculator.Rating previous, Glicko2Calculator.Rating updated, String region, Instant now) {
        DuelRatingChange change = new DuelRatingChange();
        change.id = UUID.randomUUID();
        change.matchId = matchId;
        change.playerId = player.playerId();
        change.previousMmr = previousMmr;
        change.previousPeakRating = previousPeak;
        change.newMmr = player.mmr();
        change.ratingDelta = change.newMmr - previousMmr;
        change.previousRating = previous.rating();
        change.previousDeviation = previous.deviation();
        change.previousVolatility = previous.volatility();
        change.newRating = updated.rating();
        change.newDeviation = updated.deviation();
        change.newVolatility = updated.volatility();
        change.seasonCode = player.seasonCode();
        change.region = region;
        change.createdAt = now;
        return change;
    }

    UUID matchId() { return matchId; }
    UUID playerId() { return playerId; }
    int previousMmr() { return previousMmr; }
    int previousPeakRating() { return previousPeakRating; }
    int ratingDelta() { return ratingDelta; }
    int newMmr() { return newMmr; }
}
