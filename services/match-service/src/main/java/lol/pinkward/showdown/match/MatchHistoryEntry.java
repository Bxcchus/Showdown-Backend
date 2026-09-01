package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.UUID;

record MatchHistoryEntry(
        UUID matchId,
        String region,
        String mode,
        String outcome,
        String team,
        String role,
        String championName,
        Instant playedAt,
        int previousMmr,
        int mmrDelta,
        int newMmr) {}
