package lol.pinkward.showdown.matchmaking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import lol.pinkward.showdown.contracts.MatchCancelledPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MatchCancelledConsumerTest {

    @Test
    void requeuesEligiblePlayersAndRemovesTheDecliningPlayer() throws Exception {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        MatchmakingInboxRepository inbox = mock(MatchmakingInboxRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID eventId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        List<QueueEntry> queueEntries = IntStream.range(0, 10)
                .mapToObj(index -> QueueEntry.join(
                        UUID.randomUUID(), "EUW", "operation-" + index, Instant.now()))
                .toList();
        queueEntries.forEach(entry -> entry.reserve(reservationId));
        var payload = new MatchCancelledPayload(
                matchId,
                reservationId,
                "PLAYER_DECLINED",
                queueEntries.subList(1, 10).stream().map(QueueEntry::playerId).toList());
        var envelope = new MatchCancelledEnvelope(
                eventId, "MATCH_CANCELLED", 1, Instant.now(), "match-service", matchId, null, payload);
        when(objectMapper.readValue("event", MatchCancelledEnvelope.class)).thenReturn(envelope);
        when(entries.findByReservationId(reservationId)).thenReturn(queueEntries);
        MatchCancelledConsumer consumer = new MatchCancelledConsumer(entries, inbox, objectMapper);

        consumer.consume("event");

        assertThat(queueEntries.subList(1, 10)).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo(QueueStatus.QUEUED);
            assertThat(entry.reservationId()).isNull();
        });
        verify(entries).delete(queueEntries.getFirst());
        verify(inbox).save(org.mockito.ArgumentMatchers.any(MatchmakingInboxEvent.class));
    }
}
