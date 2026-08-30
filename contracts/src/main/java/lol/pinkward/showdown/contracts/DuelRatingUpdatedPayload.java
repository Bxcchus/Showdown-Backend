package lol.pinkward.showdown.contracts;

import java.util.UUID;

/** Projection durable de la cote Glicko-2 destinée au matchmaking 1v1. */
public record DuelRatingUpdatedPayload(
        UUID playerId,
        int mmr,
        int peakMmr,
        int games,
        String season,
        String region,
        double rating,
        double ratingDeviation,
        double volatility) {

    public DuelRatingUpdatedPayload {
        if (playerId == null || mmr < 0 || peakMmr < mmr || games < 1
                || season == null || season.isBlank()
                || region == null || region.isBlank()
                || !Double.isFinite(rating)
                || !Double.isFinite(ratingDeviation)
                || ratingDeviation <= 0
                || !Double.isFinite(volatility)
                || volatility <= 0) {
            throw new IllegalArgumentException("A duel rating update requires a valid Glicko-2 rating");
        }
    }
}
