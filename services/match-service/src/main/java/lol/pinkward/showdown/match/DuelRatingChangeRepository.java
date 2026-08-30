package lol.pinkward.showdown.match;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DuelRatingChangeRepository extends JpaRepository<DuelRatingChange, UUID> {
    Optional<DuelRatingChange> findByMatchIdAndPlayerId(UUID matchId, UUID playerId);
    List<DuelRatingChange> findByPlayerIdAndMatchIdInOrderByCreatedAtDesc(
            UUID playerId, List<UUID> matchIds);
}
