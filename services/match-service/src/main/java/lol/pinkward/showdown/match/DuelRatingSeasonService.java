package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class DuelRatingSeasonService {
    private final RatingSeasonRepository seasons;
    private final DuelPlayerRatingRepository ratings;
    private final DuelSeasonRatingArchiveRepository archives;

    DuelRatingSeasonService(RatingSeasonRepository seasons, DuelPlayerRatingRepository ratings,
            DuelSeasonRatingArchiveRepository archives) {
        this.seasons = seasons;
        this.ratings = ratings;
        this.archives = archives;
    }

    RatingSeason current(Instant now) {
        return seasons.findCurrent(now).orElseThrow(() ->
                new IllegalStateException("No rating season is configured for " + now));
    }

    DuelPlayerRating prepare(UUID playerId, String region, Instant now) {
        RatingSeason season = current(now);
        DuelPlayerRating rating = ratings.findById(playerId)
                .orElseGet(() -> DuelPlayerRating.initial(playerId, season.code(), region, now));
        if (!season.code().equals(rating.seasonCode())) {
            archives.save(DuelSeasonRatingArchive.from(rating, now));
            rating.startSeason(season, region, now);
        } else {
            rating.moveRegion(region, now);
        }
        return rating;
    }
}
