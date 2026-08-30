package lol.pinkward.showdown.matchmaking;

import java.time.Clock;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class DuelRatingUpdatedConsumer {

    private final DuelRatingSnapshotRepository ratings;
    private final QueueEntryRepository entries;
    private final MatchmakingInboxRepository inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    DuelRatingUpdatedConsumer(
            DuelRatingSnapshotRepository ratings,
            QueueEntryRepository entries,
            MatchmakingInboxRepository inbox,
            ObjectMapper objectMapper) {
        this.ratings = ratings;
        this.entries = entries;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfiguration.DUEL_RATING_UPDATED_QUEUE)
    @Transactional
    public void consume(String message) {
        DuelRatingUpdatedEnvelope event = deserialize(message);
        if (!"DUEL_RATING_UPDATED".equals(event.eventType()) || event.eventVersion() != 1
                || !"match-service".equals(event.producer())) {
            throw new IllegalArgumentException("Unsupported duel rating event");
        }
        if (inbox.existsById(event.eventId())) return;

        var payload = event.payload();
        var existing = ratings.findById(payload.playerId());
        var snapshot = existing.orElseGet(() -> DuelRatingSnapshot.create(payload, event.occurredAt()));
        boolean applied = existing.isEmpty() || snapshot.updateIfNewer(payload, event.occurredAt());
        ratings.save(snapshot);
        if (applied) {
            entries.findByPlayerId(payload.playerId())
                    .filter(entry -> "ONE_V_ONE".equals(entry.mode()))
                    .filter(entry -> payload.region().equals(entry.region()))
                    .ifPresent(entry -> entry.updateSkill(
                            snapshot.mmr(), snapshot.queueMean(), snapshot.queueDeviation()));
        }
        inbox.save(MatchmakingInboxEvent.received(event.eventId(), clock.instant()));
    }

    private DuelRatingUpdatedEnvelope deserialize(String message) {
        try {
            return objectMapper.readValue(message, DuelRatingUpdatedEnvelope.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid DUEL_RATING_UPDATED event", exception);
        }
    }
}
