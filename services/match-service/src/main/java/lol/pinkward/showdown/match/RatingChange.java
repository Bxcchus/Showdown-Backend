package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rating_changes")
class RatingChange {

    @Id
    private UUID id;

    @Column(name = "match_id", nullable = false)
    private UUID matchId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "previous_rating", nullable = false)
    private int previousRating;

    @Column(name = "previous_peak_rating", nullable = false)
    private int previousPeakRating;

    @Column(name = "rating_delta", nullable = false)
    private int ratingDelta;

    @Column(name = "new_rating", nullable = false)
    private int newRating;

    @Column(name = "previous_skill_mean", nullable = false)
    private double previousSkillMean;

    @Column(name = "previous_skill_deviation", nullable = false)
    private double previousSkillDeviation;

    @Column(name = "new_skill_mean", nullable = false)
    private double newSkillMean;

    @Column(name = "new_skill_deviation", nullable = false)
    private double newSkillDeviation;

    @Column(name = "season_code", nullable = false, length = 16)
    private String seasonCode;

    @Column(nullable = false, length = 16)
    private String region;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RatingChange() {}

    static RatingChange of(
            UUID matchId,
            UUID playerId,
            int previous,
            int previousPeak,
            TrueSkillCalculator.Skill previousSkill,
            TrueSkillCalculator.Skill newSkill,
            int newRating,
            String seasonCode,
            String region,
            Instant now) {
        RatingChange change = new RatingChange();
        change.id = UUID.randomUUID();
        change.matchId = matchId;
        change.playerId = playerId;
        change.previousRating = previous;
        change.previousPeakRating = previousPeak;
        change.ratingDelta = newRating - previous;
        change.newRating = newRating;
        change.previousSkillMean = previousSkill.mean();
        change.previousSkillDeviation = previousSkill.deviation();
        change.newSkillMean = newSkill.mean();
        change.newSkillDeviation = newSkill.deviation();
        change.seasonCode = seasonCode;
        change.region = region;
        change.createdAt = now;
        return change;
    }

    UUID matchId() { return matchId; }
    UUID playerId() { return playerId; }
    int previousRating() { return previousRating; }
    int previousPeakRating() { return previousPeakRating; }
    int ratingDelta() { return ratingDelta; }
    int newRating() { return newRating; }
    double previousSkillMean() { return previousSkillMean; }
    double previousSkillDeviation() { return previousSkillDeviation; }
    double newSkillMean() { return newSkillMean; }
    double newSkillDeviation() { return newSkillDeviation; }
}
