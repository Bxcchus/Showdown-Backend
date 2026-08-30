package lol.pinkward.showdown.match;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MatchPlayerRepository extends JpaRepository<MatchPlayer, UUID> {

    List<MatchPlayer> findByMatchIdOrderByTeamAscPlayerIdAsc(UUID matchId);

    Optional<MatchPlayer> findByMatchIdAndPlayerId(UUID matchId, UUID playerId);
}
