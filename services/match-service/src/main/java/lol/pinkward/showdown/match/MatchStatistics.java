package lol.pinkward.showdown.match;

import java.util.List;
import java.util.Map;

record MatchStatistics(
        int mmr,
        int peakMmr,
        double skillMean,
        double skillDeviation,
        String season,
        String region,
        int placementGamesRemaining,
        int progression,
        String rank,
        int games,
        int wins,
        int losses,
        double winRate,
        Map<String, Long> gamesByRole,
        List<String> recentForm) {}
