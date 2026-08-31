package lol.pinkward.showdown.match;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TeamMatchWatcherResultRepository extends JpaRepository<TeamMatchWatcherResult, UUID> {
    Optional<TeamMatchWatcherResult> findByMatchIdAndReporterId(UUID matchId, UUID reporterId);
    List<TeamMatchWatcherResult> findAllByMatchId(UUID matchId);
}
