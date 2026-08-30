package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RatingSeasonRepository extends JpaRepository<RatingSeason, String> {
    @Query("select season from RatingSeason season where season.startsAt <= :now and season.endsAt > :now")
    Optional<RatingSeason> findCurrent(Instant now);
}
