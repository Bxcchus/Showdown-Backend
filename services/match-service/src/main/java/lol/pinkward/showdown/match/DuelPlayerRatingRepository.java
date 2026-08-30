package lol.pinkward.showdown.match;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DuelPlayerRatingRepository extends JpaRepository<DuelPlayerRating, UUID> {
    List<DuelPlayerRating> findBySeasonCodeAndRegionAndGamesGreaterThanEqualOrderByRatingDescUpdatedAtAscPlayerIdAsc(
            String seasonCode, String region, int games);
}
