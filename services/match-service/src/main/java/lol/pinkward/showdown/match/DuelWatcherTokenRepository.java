package lol.pinkward.showdown.match;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DuelWatcherTokenRepository extends JpaRepository<DuelWatcherToken, UUID> {
    Optional<DuelWatcherToken> findByTokenHash(String tokenHash);
    Optional<DuelWatcherToken> findByMatchIdAndPlayerId(UUID matchId, UUID playerId);
    List<DuelWatcherToken> findAllByMatchId(UUID matchId);
}
