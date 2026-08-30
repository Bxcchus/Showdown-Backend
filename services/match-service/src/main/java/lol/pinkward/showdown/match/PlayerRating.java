package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "player_ratings")
class PlayerRating {

    static final int INITIAL_RATING = TrueSkillCalculator.INITIAL_MMR;

    @Id
    @Column(name = "player_id")
    private UUID playerId;

    @Column(nullable = false)
    private int rating;

    @Column(name = "peak_rating", nullable = false)
    private int peakRating;

    @Column(nullable = false)
    private int games;

    @Column(nullable = false)
    private int wins;

    @Column(nullable = false)
    private int losses;

    @Column(name = "skill_mean", nullable = false)
    private double skillMean;

    @Column(name = "skill_deviation", nullable = false)
    private double skillDeviation;

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

    protected PlayerRating() {}

    static PlayerRating initial(UUID playerId, Instant now) {
        return initial(playerId, "S2026", "EUW", now);
    }

    static PlayerRating initial(UUID playerId, String seasonCode, String region, Instant now) {
        PlayerRating rating = new PlayerRating();
        rating.playerId = playerId;
        rating.rating = INITIAL_RATING;
        rating.peakRating = INITIAL_RATING;
        rating.skillMean = TrueSkillCalculator.INITIAL_MEAN;
        rating.skillDeviation = TrueSkillCalculator.INITIAL_DEVIATION;
        rating.seasonCode = seasonCode;
        rating.region = region;
        rating.seasonStartRating = INITIAL_RATING;
        rating.updatedAt = now;
        return rating;
    }

    void startSeason(RatingSeason season, String region, Instant now) {
        double mean = TrueSkillCalculator.INITIAL_MEAN
                + (skillMean - TrueSkillCalculator.INITIAL_MEAN) * season.resetFactor();
        double deviation = Math.min(TrueSkillCalculator.INITIAL_DEVIATION,
                Math.sqrt(skillDeviation * skillDeviation
                        + TrueSkillCalculator.INITIAL_DEVIATION * TrueSkillCalculator.INITIAL_DEVIATION
                        * (1.0 - season.resetFactor())));
        skillMean = mean;
        skillDeviation = deviation;
        rating = TrueSkillCalculator.displayMmr(new TrueSkillCalculator.Skill(mean, deviation));
        peakRating = rating;
        seasonStartRating = rating;
        games = 0; wins = 0; losses = 0;
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

    void apply(boolean won, TrueSkillCalculator.Skill skill, Instant now) {
        skillMean = skill.mean();
        skillDeviation = skill.deviation();
        rating = TrueSkillCalculator.displayMmr(skill);
        peakRating = Math.max(peakRating, rating);
        games++;
        if (won) wins++; else losses++;
        updatedAt = now;
    }

    UUID playerId() { return playerId; }
    int rating() { return rating; }
    int peakRating() { return peakRating; }
    int games() { return games; }
    int wins() { return wins; }
    int losses() { return losses; }
    double skillMean() { return skillMean; }
    double skillDeviation() { return skillDeviation; }
    String seasonCode() { return seasonCode; }
    String region() { return region; }
    int progression() { return rating - seasonStartRating; }
    TrueSkillCalculator.Skill skill() { return new TrueSkillCalculator.Skill(skillMean, skillDeviation); }
}
