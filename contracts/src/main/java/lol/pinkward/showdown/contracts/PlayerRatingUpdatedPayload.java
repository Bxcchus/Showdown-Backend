package lol.pinkward.showdown.contracts;

import java.util.UUID;

public record PlayerRatingUpdatedPayload(
        UUID playerId,
        int rating,
        int peakRating,
        int games,
        double skillMean,
        double skillDeviation) {

    public PlayerRatingUpdatedPayload {
        if (playerId == null || rating < 0 || peakRating < rating || games < 1
                || !Double.isFinite(skillMean)
                || !Double.isFinite(skillDeviation)
                || skillDeviation <= 0) {
            throw new IllegalArgumentException("A rating update requires a valid player rating");
        }
    }
}
