package lol.pinkward.showdown.match;

import java.util.UUID;

record DuelLeaderboardEntry(
        int position,
        UUID playerId,
        int mmr,
        double ratingDeviation,
        boolean provisional,
        int games,
        int wins,
        double winRate,
        int progression) {}
