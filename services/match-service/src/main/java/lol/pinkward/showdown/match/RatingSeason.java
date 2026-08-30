package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "rating_seasons")
class RatingSeason {
    @Id private String code;
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(name = "ends_at", nullable = false) private Instant endsAt;
    @Column(name = "placement_games", nullable = false) private int placementGames;
    @Column(name = "reset_factor", nullable = false) private double resetFactor;
    protected RatingSeason() {}
    static RatingSeason of(String code, Instant startsAt, Instant endsAt, int placementGames, double resetFactor) {
        RatingSeason season = new RatingSeason();
        season.code = code; season.startsAt = startsAt; season.endsAt = endsAt;
        season.placementGames = placementGames; season.resetFactor = resetFactor;
        return season;
    }
    String code() { return code; }
    Instant startsAt() { return startsAt; }
    Instant endsAt() { return endsAt; }
    int placementGames() { return placementGames; }
    double resetFactor() { return resetFactor; }
}
