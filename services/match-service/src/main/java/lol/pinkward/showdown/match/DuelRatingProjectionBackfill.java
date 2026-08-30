package lol.pinkward.showdown.match;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.UUID;
import lol.pinkward.showdown.contracts.DuelRatingUpdatedPayload;
import lol.pinkward.showdown.contracts.EventEnvelope;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Republishes durable duel ratings so an empty matchmaking projection can recover. */
@Component
class DuelRatingProjectionBackfill {

    private final DuelPlayerRatingRepository ratings;
    private final MatchOutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    DuelRatingProjectionBackfill(
            DuelPlayerRatingRepository ratings,
            MatchOutboxRepository outbox,
            ObjectMapper objectMapper) {
        this.ratings = ratings;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @EventListener(classes = ApplicationReadyEvent.class)
    @Transactional
    void republishCurrentRatings() {
        var now = clock.instant();
        for (DuelPlayerRating rating : ratings.findAll()) {
            if (rating.games() < 1) continue;
            UUID eventId = deterministicEventId(rating);
            if (outbox.existsById(eventId)) continue;
            var envelope = new EventEnvelope<>(
                    eventId,
                    MatchApplicationService.DUEL_RATING_UPDATED,
                    1,
                    now,
                    "match-service",
                    rating.playerId(),
                    null,
                    new DuelRatingUpdatedPayload(
                            rating.playerId(),
                            rating.mmr(),
                            rating.peakRating(),
                            rating.games(),
                            rating.seasonCode(),
                            rating.region(),
                            rating.rating(),
                            rating.ratingDeviation(),
                            rating.volatility()));
            outbox.save(MatchOutboxEvent.pending(
                    eventId,
                    MatchApplicationService.DUEL_RATING_UPDATED,
                    MatchApplicationService.DUEL_RATING_UPDATED_KEY,
                    serialize(envelope),
                    now));
        }
    }

    private static UUID deterministicEventId(DuelPlayerRating rating) {
        String version = String.format(
                Locale.ROOT,
                "duel-rating:%s:%s:%s:%d:%.8f:%.8f:%.8f",
                rating.playerId(),
                rating.seasonCode(),
                rating.region(),
                rating.games(),
                rating.rating(),
                rating.ratingDeviation(),
                rating.volatility());
        return UUID.nameUUIDFromBytes(version.getBytes(StandardCharsets.UTF_8));
    }

    private String serialize(Object envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize DUEL_RATING_UPDATED", exception);
        }
    }
}
