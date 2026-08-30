package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.List;

record LeaderboardSnapshot(
        String season,
        String region,
        Instant startsAt,
        Instant endsAt,
        int placementGames,
        int totalEntries,
        LeaderboardEntry viewer,
        List<LeaderboardEntry> entries) {}
