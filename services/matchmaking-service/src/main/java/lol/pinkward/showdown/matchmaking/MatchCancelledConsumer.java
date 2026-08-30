package lol.pinkward.showdown.matchmaking;

import java.time.Clock;
import java.util.HashSet;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class MatchCancelledConsumer {

    private final QueueEntryRepository entries;
    private final MatchmakingInboxRepository inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    MatchCancelledConsumer(
            QueueEntryRepository entries,
            MatchmakingInboxRepository inbox,
            ObjectMapper objectMapper) {
        this.entries = entries;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfiguration.MATCH_CANCELLED_QUEUE)
    @Transactional
    public void consume(String message) {
        MatchCancelledEnvelope event = deserialize(message);
        if (!"MATCH_CANCELLED".equals(event.eventType()) || event.eventVersion() != 1
                || !"match-service".equals(event.producer())) {
            throw new IllegalArgumentException("Unsupported match event");
        }
        if (inbox.existsById(event.eventId())) return;

        var reserved = entries.findByReservationId(event.payload().reservationId());
        var reservedPlayers = reserved.stream().map(QueueEntry::playerId).collect(java.util.stream.Collectors.toSet());
        var playersToRequeue = new HashSet<>(event.payload().requeuePlayerIds());
        if (!reservedPlayers.containsAll(playersToRequeue)) {
            throw new IllegalStateException("Requeue players do not belong to the cancelled reservation");
        }
        var now = clock.instant();
        reserved.forEach(entry -> {
            if (playersToRequeue.contains(entry.playerId()) && !entry.isLocalBot()) {
                entry.requeue(now);
            } else {
                entries.delete(entry);
            }
        });
        inbox.save(MatchmakingInboxEvent.received(event.eventId(), now));
    }

    private MatchCancelledEnvelope deserialize(String message) {
        try {
            return objectMapper.readValue(message, MatchCancelledEnvelope.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid MATCH_CANCELLED event", exception);
        }
    }
}
