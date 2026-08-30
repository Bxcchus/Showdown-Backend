package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "duel_season_rating_archives")
class DuelSeasonRatingArchive {
    @Id private UUID id;
    @Column(name = "player_id", nullable = false) private UUID playerId;
    @Column(name = "season_code", nullable = false, length = 16) private String seasonCode;
    @Column(nullable = false, length = 16) private String region;
    @Column(name = "final_mmr", nullable = false) private int finalMmr;
    @Column(name = "peak_rating", nullable = false) private int peakRating;
    @Column(nullable = false) private int games;
    @Column(nullable = false) private int wins;
    @Column(nullable = false) private int losses;
    @Column(nullable = false) private double rating;
    @Column(name = "rating_deviation", nullable = false) private double ratingDeviation;
    @Column(nullable = false) private double volatility;
    @Column(name = "archived_at", nullable = false) private Instant archivedAt;

    protected DuelSeasonRatingArchive() {}

    static DuelSeasonRatingArchive from(DuelPlayerRating rating, Instant now) {
        DuelSeasonRatingArchive archive = new DuelSeasonRatingArchive();
        archive.id = UUID.randomUUID();
        archive.playerId = rating.playerId();
        archive.seasonCode = rating.seasonCode();
        archive.region = rating.region();
        archive.finalMmr = rating.mmr();
        archive.peakRating = rating.peakRating();
        archive.games = rating.games();
        archive.wins = rating.wins();
        archive.losses = rating.losses();
        archive.rating = rating.rating();
        archive.ratingDeviation = rating.ratingDeviation();
        archive.volatility = rating.volatility();
        archive.archivedAt = now;
        return archive;
    }
}
