package lol.pinkward.showdown.match;

import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlayerRatingRepository extends JpaRepository<PlayerRating, UUID> {
    List<PlayerRating> findBySeasonCodeAndRegionAndGamesGreaterThanEqualOrderByRatingDescUpdatedAtAscPlayerIdAsc(
            String seasonCode, String region, int games);
}
