package lol.pinkward.showdown.player;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RiotLinkChallengeRepository extends JpaRepository<RiotLinkChallenge, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from RiotLinkChallenge challenge where challenge.challengeId = :id")
    Optional<RiotLinkChallenge> findByIdForUpdate(@Param("id") UUID id);

    void deleteByPlayerIdAndConsumedAtIsNull(UUID playerId);

    void deleteByExpiresAtBefore(Instant cutoff);
}
