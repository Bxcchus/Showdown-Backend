package lol.pinkward.showdown.match;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RatingChangeRepository extends JpaRepository<RatingChange, UUID> {
    Optional<RatingChange> findByMatchIdAndPlayerId(UUID matchId, UUID playerId);
    List<RatingChange> findByPlayerIdAndMatchIdInOrderByCreatedAtDesc(
            UUID playerId, List<UUID> matchIds);
}
