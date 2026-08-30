package lol.pinkward.showdown.match;

import java.util.UUID;

record LeaderboardEntry(
        int position,
        UUID playerId,
        int mmr,
        String rank,
        int games,
        int wins,
        double winRate,
        int progression) {}
