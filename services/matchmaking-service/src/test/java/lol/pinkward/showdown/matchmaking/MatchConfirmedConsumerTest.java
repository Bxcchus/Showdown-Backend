package lol.pinkward.showdown.matchmaking;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import lol.pinkward.showdown.contracts.MatchConfirmedPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MatchConfirmedConsumerTest {

    @Test
    void releasesEveryQueueEntryAfterConfirmation() throws Exception {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        MatchmakingInboxRepository inbox = mock(MatchmakingInboxRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID reservationId = UUID.randomUUID();
        List<QueueEntry> reserved = IntStream.range(0, 10)
                .mapToObj(index -> QueueEntry.join(
                        UUID.randomUUID(), "EUW", "operation-" + index, Instant.now()))
                .toList();
        reserved.forEach(entry -> entry.reserve(reservationId));
        UUID eventId = UUID.randomUUID();
        var envelope = new MatchConfirmedEnvelope(
                eventId,
                "MATCH_CONFIRMED",
                1,
                Instant.now(),
                "match-service",
                UUID.randomUUID(),
                null,
                new MatchConfirmedPayload(
                        UUID.randomUUID(),
                        reservationId,
                        reserved.stream().map(QueueEntry::playerId).toList()));
        when(objectMapper.readValue("event", MatchConfirmedEnvelope.class)).thenReturn(envelope);
        when(entries.findByReservationId(reservationId)).thenReturn(reserved);
        var consumer = new MatchConfirmedConsumer(entries, inbox, objectMapper);

        consumer.consume("event");

        verify(entries).deleteAll(reserved);
        verify(inbox).save(any(MatchmakingInboxEvent.class));
    }
}
