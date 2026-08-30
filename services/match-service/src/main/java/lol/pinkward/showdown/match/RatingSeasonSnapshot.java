package lol.pinkward.showdown.match;

import java.time.Instant;

record RatingSeasonSnapshot(
        String code,
        Instant startsAt,
        Instant endsAt,
        int placementGames,
        double resetFactor,
        boolean active) {

    static RatingSeasonSnapshot from(RatingSeason season, Instant now) {
        return new RatingSeasonSnapshot(
                season.code(),
                season.startsAt(),
                season.endsAt(),
                season.placementGames(),
                season.resetFactor(),
                !now.isBefore(season.startsAt()) && now.isBefore(season.endsAt()));
    }
}
