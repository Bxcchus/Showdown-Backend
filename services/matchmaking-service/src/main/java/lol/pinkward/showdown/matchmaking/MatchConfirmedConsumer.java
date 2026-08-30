package lol.pinkward.showdown.matchmaking;

import java.time.Clock;
import java.util.HashSet;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class MatchConfirmedConsumer {

    private final QueueEntryRepository entries;
    private final MatchmakingInboxRepository inbox;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    MatchConfirmedConsumer(
            QueueEntryRepository entries,
            MatchmakingInboxRepository inbox,
            ObjectMapper objectMapper) {
        this.entries = entries;
        this.inbox = inbox;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfiguration.MATCH_CONFIRMED_QUEUE)
    @Transactional
    public void consume(String message) {
        MatchConfirmedEnvelope event = deserialize(message);
        if (!"MATCH_CONFIRMED".equals(event.eventType()) || event.eventVersion() != 1
                || !"match-service".equals(event.producer())) {
            throw new IllegalArgumentException("Unsupported match event");
        }
        if (inbox.existsById(event.eventId())) return;

        var reserved = entries.findByReservationId(event.payload().reservationId());
        var reservedPlayers = reserved.stream().map(QueueEntry::playerId)
                .collect(java.util.stream.Collectors.toSet());
        if (!reserved.isEmpty()
                && !reservedPlayers.equals(new HashSet<>(event.payload().playerIds()))) {
            throw new IllegalStateException("Reservation players do not match MATCH_CONFIRMED");
        }
        entries.deleteAll(reserved);
        inbox.save(MatchmakingInboxEvent.received(event.eventId(), clock.instant()));
    }

    private MatchConfirmedEnvelope deserialize(String message) {
        try {
            return objectMapper.readValue(message, MatchConfirmedEnvelope.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid MATCH_CONFIRMED event", exception);
        }
    }
}
