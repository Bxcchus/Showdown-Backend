package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.List;

record DuelLeaderboardSnapshot(
        String algorithm,
        String season,
        String region,
        Instant startsAt,
        Instant endsAt,
        int placementGames,
        int totalEntries,
        DuelLeaderboardEntry viewer,
        List<DuelLeaderboardEntry> entries) {}
