package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface GameMatchRepository extends JpaRepository<GameMatch, UUID> {

    boolean existsByReservationId(UUID reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select match from GameMatch match where match.id = :matchId")
    Optional<GameMatch> findByIdForUpdate(@Param("matchId") UUID matchId);

    @Query(value = """
            SELECT m.* FROM matches m
            JOIN match_players p ON p.match_id = m.id
            WHERE p.player_id = :playerId
              AND m.status = 'READY_CHECK'
            ORDER BY m.created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<GameMatch> findCurrentForPlayer(@Param("playerId") UUID playerId);

    @Query(value = """
            SELECT m.* FROM matches m
            JOIN match_players p ON p.match_id = m.id
            WHERE p.player_id = :playerId
              AND m.status = 'CONFIRMED'
            ORDER BY m.created_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<GameMatch> findCurrentLobbyForPlayer(@Param("playerId") UUID playerId);

    @Query(value = """
            SELECT m.* FROM matches m JOIN match_players p ON p.match_id = m.id
            WHERE p.player_id = :playerId AND m.status IN ('READY_CHECK', 'CONFIRMED')
            ORDER BY m.created_at DESC LIMIT 1
            """, nativeQuery = true)
    Optional<GameMatch> findActiveForPlayer(@Param("playerId") UUID playerId);

    @Query(value = """
            SELECT m.* FROM matches m
            JOIN match_players p ON p.match_id = m.id
            WHERE m.id = :matchId
              AND p.player_id = :playerId
              AND m.status = 'COMPLETED'
              AND m.winning_team IS NOT NULL
            """, nativeQuery = true)
    Optional<GameMatch> findCompletedForPlayer(
            @Param("matchId") UUID matchId,
            @Param("playerId") UUID playerId);

    @Query(value = """
            SELECT m.* FROM matches m
            JOIN match_players p ON p.match_id = m.id
            WHERE p.player_id = :playerId
              AND m.status = 'COMPLETED'
              AND m.winning_team IS NOT NULL
              AND (CAST(:region AS VARCHAR) IS NULL OR m.region = :region)
              AND (CAST(:mode AS VARCHAR) IS NULL OR m.mode = :mode)
              AND (CAST(:role AS VARCHAR) IS NULL OR p.assigned_role = :role)
              AND (CAST(:outcome AS VARCHAR) IS NULL
                   OR (:outcome = 'VICTORY' AND p.team = m.winning_team)
                   OR (:outcome = 'DEFEAT' AND p.team <> m.winning_team))
            """,
            countQuery = """
            SELECT COUNT(*) FROM matches m
            JOIN match_players p ON p.match_id = m.id
            WHERE p.player_id = :playerId
              AND m.status = 'COMPLETED'
              AND m.winning_team IS NOT NULL
              AND (CAST(:region AS VARCHAR) IS NULL OR m.region = :region)
              AND (CAST(:mode AS VARCHAR) IS NULL OR m.mode = :mode)
              AND (CAST(:role AS VARCHAR) IS NULL OR p.assigned_role = :role)
              AND (CAST(:outcome AS VARCHAR) IS NULL
                   OR (:outcome = 'VICTORY' AND p.team = m.winning_team)
                   OR (:outcome = 'DEFEAT' AND p.team <> m.winning_team))
            """, nativeQuery = true)
    Page<GameMatch> findHistoryForPlayer(
            @Param("playerId") UUID playerId,
            @Param("region") String region,
            @Param("mode") String mode,
            @Param("role") String role,
            @Param("outcome") String outcome,
            Pageable pageable);

    List<GameMatch> findByStatusAndReadyDeadlineBefore(MatchStatus status, Instant deadline);
}
