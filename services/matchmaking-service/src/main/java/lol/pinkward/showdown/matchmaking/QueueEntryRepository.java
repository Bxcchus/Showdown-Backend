package lol.pinkward.showdown.matchmaking;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface QueueEntryRepository extends JpaRepository<QueueEntry, UUID> {

    Optional<QueueEntry> findByPlayerId(UUID playerId);

    List<QueueEntry> findByPartyIdOrderByJoinedAtAscIdAsc(UUID partyId);

    List<QueueEntry> findByReservationId(UUID reservationId);

    @Query(value = """
            SELECT *
            FROM queue_entries
            WHERE region = :region
              AND mode = :mode
              AND status = 'QUEUED'
            ORDER BY joined_at, id
            """, nativeQuery = true)
    List<QueueEntry> findQueuedForBotFill(@Param("region") String region, @Param("mode") String mode);

    @Query(value = """
            SELECT *
            FROM queue_entries
            WHERE region = :region
              AND mode = :mode
              AND status = 'QUEUED'
            ORDER BY joined_at, id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<QueueEntry> lockCandidates(@Param("region") String region, @Param("mode") String mode);
}
