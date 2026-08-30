package lol.pinkward.showdown.match;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DuelWatcherObservationRepository extends JpaRepository<DuelWatcherObservation, UUID> {
    Optional<DuelWatcherObservation> findByMatchIdAndReporterIdAndObjective(
            UUID matchId, UUID reporterId, DuelObjective objective);
    long countByMatchIdAndObjectiveAndWinnerPlayerId(UUID matchId, DuelObjective objective, UUID winnerPlayerId);
}
