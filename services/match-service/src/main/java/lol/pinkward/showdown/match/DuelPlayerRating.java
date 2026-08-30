package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "duel_player_ratings")
class DuelPlayerRating {

    static final int INITIAL_MMR = (int) Glicko2Calculator.INITIAL_RATING;
    static final double PROVISIONAL_DEVIATION = 160.0;

    @Id
    @Column(name = "player_id")
    private UUID playerId;

    @Column(nullable = false)
    private double rating;

    @Column(name = "rating_deviation", nullable = false)
    private double ratingDeviation;

    @Column(nullable = false)
    private double volatility;

    @Column(name = "peak_rating", nullable = false)
    private int peakRating;

    @Column(nullable = false)
    private int games;

    @Column(nullable = false)
    private int wins;

    @Column(nullable = false)
    private int losses;

    @Column(name = "season_code", nullable = false, length = 16)
    private String seasonCode;

    @Column(nullable = false, length = 16)
    private String region;

    @Column(name = "season_start_rating", nullable = false)
    private int seasonStartRating;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected DuelPlayerRating() {}

    static DuelPlayerRating initial(UUID playerId, String seasonCode, String region, Instant now) {
        DuelPlayerRating result = new DuelPlayerRating();
        result.playerId = playerId;
        result.rating = Glicko2Calculator.INITIAL_RATING;
        result.ratingDeviation = Glicko2Calculator.INITIAL_DEVIATION;
        result.volatility = Glicko2Calculator.INITIAL_VOLATILITY;
        result.volatility = Glicko2Calculator.INITIAL_VOLATILITY;
        result.peakRating = INITIAL_MMR;
        result.seasonCode = seasonCode;
        result.region = region;
        result.seasonStartRating = INITIAL_MMR;
        result.updatedAt = now;
        return result;
    }

    void startSeason(RatingSeason season, String region, Instant now) {
        rating = Glicko2Calculator.INITIAL_RATING
                + (rating - Glicko2Calculator.INITIAL_RATING) * season.resetFactor();
        ratingDeviation = Math.min(Glicko2Calculator.INITIAL_DEVIATION,
                Math.sqrt(ratingDeviation * ratingDeviation
                        + Glicko2Calculator.INITIAL_DEVIATION * Glicko2Calculator.INITIAL_DEVIATION
                        * (1.0 - season.resetFactor())));
        peakRating = mmr();
        seasonStartRating = mmr();
        games = 0;
        wins = 0;
        losses = 0;
        seasonCode = season.code();
        this.region = region;
        updatedAt = now;
    }

    void moveRegion(String region, Instant now) {
        if (!this.region.equals(region)) {
            this.region = region;
            updatedAt = now;
        }
    }

    void apply(boolean won, Glicko2Calculator.Rating updated, Instant now) {
        rating = updated.rating();
        ratingDeviation = updated.deviation();
        volatility = updated.volatility();
        peakRating = Math.max(peakRating, mmr());
        games++;
        if (won) wins++; else losses++;
        updatedAt = now;
    }

    UUID playerId() { return playerId; }
    int mmr() { return Math.max(0, (int) Math.round(rating)); }
    int peakRating() { return peakRating; }
    int games() { return games; }
    int wins() { return wins; }
    int losses() { return losses; }
    double rating() { return rating; }
    double ratingDeviation() { return ratingDeviation; }
    double volatility() { return volatility; }
    String seasonCode() { return seasonCode; }
    String region() { return region; }
    int progression() { return mmr() - seasonStartRating; }
    boolean provisional(int placementGames) {
        return games < placementGames || ratingDeviation > PROVISIONAL_DEVIATION;
    }
    Glicko2Calculator.Rating distribution() {
        return new Glicko2Calculator.Rating(rating, ratingDeviation, volatility);
    }
}
