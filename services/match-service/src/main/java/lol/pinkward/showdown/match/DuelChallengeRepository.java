package lol.pinkward.showdown.match;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface DuelChallengeRepository extends JpaRepository<DuelChallenge, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from DuelChallenge challenge where challenge.id = :id")
    Optional<DuelChallenge> findByIdForUpdate(@Param("id") UUID id);

    @Query("select challenge from DuelChallenge challenge where (challenge.challengerId = :playerId or challenge.opponentId = :playerId) order by challenge.createdAt desc")
    List<DuelChallenge> findForPlayer(@Param("playerId") UUID playerId);

    @Query("select count(challenge) > 0 from DuelChallenge challenge where challenge.status = :status and challenge.expiresAt > :now and (challenge.challengerId in (:first, :second) or challenge.opponentId in (:first, :second))")
    boolean hasPendingForEither(@Param("first") UUID first, @Param("second") UUID second,
            @Param("status") DuelChallengeStatus status, @Param("now") Instant now);

    List<DuelChallenge> findByStatusAndExpiresAtBefore(DuelChallengeStatus status, Instant now);
    Optional<DuelChallenge> findByMatchId(UUID matchId);
}
