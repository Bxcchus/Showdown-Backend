package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "season_rating_archives")
class SeasonRatingArchive {
    @Id private UUID id;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "season_code", nullable = false) private String seasonCode;
    @Column(nullable = false) private String region;
    @Column(name = "final_rating", nullable = false) private int finalRating;
    @Column(name = "peak_rating", nullable = false) private int peakRating;
    @Column(nullable = false) private int games;
    @Column(nullable = false) private int wins;
    @Column(nullable = false) private int losses;
    @Column(name = "skill_mean", nullable = false) private double skillMean;
    @Column(name = "skill_deviation", nullable = false) private double skillDeviation;
    @Column(name = "archived_at", nullable = false) private Instant archivedAt;
    protected SeasonRatingArchive() {}
    static SeasonRatingArchive from(PlayerRating rating, Instant now) {
        var archive = new SeasonRatingArchive();
        archive.id = UUID.randomUUID(); archive.playerId = rating.playerId();
        archive.seasonCode = rating.seasonCode(); archive.region = rating.region();
        archive.finalRating = rating.rating(); archive.peakRating = rating.peakRating();
        archive.games = rating.games(); archive.wins = rating.wins(); archive.losses = rating.losses();
        archive.skillMean = rating.skillMean(); archive.skillDeviation = rating.skillDeviation();
        archive.archivedAt = now;
        return archive;
    }
}
