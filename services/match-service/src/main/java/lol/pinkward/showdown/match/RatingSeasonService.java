package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class RatingSeasonService {
    private final RatingSeasonRepository seasons;
    private final PlayerRatingRepository ratings;
    private final SeasonRatingArchiveRepository archives;

    RatingSeasonService(RatingSeasonRepository seasons, PlayerRatingRepository ratings,
            SeasonRatingArchiveRepository archives) {
        this.seasons = seasons; this.ratings = ratings; this.archives = archives;
    }

    RatingSeason current(Instant now) {
        return seasons.findCurrent(now).orElseThrow(() ->
                new IllegalStateException("No rating season is configured for " + now));
    }

    java.util.List<RatingSeason> all() {
        return seasons.findAll(org.springframework.data.domain.Sort.by("startsAt"));
    }

    PlayerRating prepare(UUID playerId, String region, Instant now) {
        RatingSeason season = current(now);
        PlayerRating rating = ratings.findById(playerId)
                .orElseGet(() -> PlayerRating.initial(playerId, season.code(), region, now));
        if (!season.code().equals(rating.seasonCode())) {
            archives.save(SeasonRatingArchive.from(rating, now));
            rating.startSeason(season, region, now);
        } else {
            rating.moveRegion(region, now);
        }
        return rating;
    }
}
