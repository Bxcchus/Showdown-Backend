package lol.pinkward.showdown.match;

record DuelStatistics(
        int mmr,
        int peakMmr,
        double rating,
        double ratingDeviation,
        double volatility,
        String algorithm,
        String season,
        String region,
        int placementGamesRemaining,
        boolean provisional,
        int progression,
        int games,
        int wins,
        int losses,
        double winRate) {}
