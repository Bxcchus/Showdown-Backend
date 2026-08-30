package lol.pinkward.showdown.matchmaking;

import java.time.Clock;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class PlayerRatingUpdatedConsumer {

    private final PlayerRatingSnapshotRepository ratings;
    private final QueueEntryRepository entries;
    private final MatchmakingInboxRepository inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    PlayerRatingUpdatedConsumer(
            PlayerRatingSnapshotRepository ratings,
            QueueEntryRepository entries,
            MatchmakingInboxRepository inbox,
            ObjectMapper objectMapper) {
        this.ratings = ratings;
        this.entries = entries;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfiguration.PLAYER_RATING_UPDATED_QUEUE)
    @Transactional
    public void consume(String message) {
        PlayerRatingUpdatedEnvelope event = deserialize(message);
        if (!"PLAYER_RATING_UPDATED".equals(event.eventType()) || event.eventVersion() != 2
                || !"match-service".equals(event.producer())) {
            throw new IllegalArgumentException("Unsupported rating event");
        }
        if (inbox.existsById(event.eventId())) return;

        var payload = event.payload();
        var existing = ratings.findById(payload.playerId());
        var snapshot = existing.orElseGet(() -> PlayerRatingSnapshot.create(
                payload.playerId(),
                payload.rating(),
                payload.peakRating(),
                payload.games(),
                payload.skillMean(),
                payload.skillDeviation(),
                event.occurredAt()));
        boolean applied = existing.isEmpty() || snapshot.updateIfNewer(
                payload.rating(),
                payload.peakRating(),
                payload.games(),
                payload.skillMean(),
                payload.skillDeviation(),
                event.occurredAt());
        ratings.save(snapshot);
        if (applied) {
            entries.findByPlayerId(payload.playerId())
                    .filter(entry -> "FIVE_V_FIVE".equals(entry.mode()))
                    .ifPresent(entry -> entry.updateSkill(
                            payload.rating(), payload.skillMean(), payload.skillDeviation()));
        }
        inbox.save(MatchmakingInboxEvent.received(event.eventId(), clock.instant()));
    }

    private PlayerRatingUpdatedEnvelope deserialize(String message) {
        try {
            return objectMapper.readValue(message, PlayerRatingUpdatedEnvelope.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid PLAYER_RATING_UPDATED event", exception);
        }
    }
}
