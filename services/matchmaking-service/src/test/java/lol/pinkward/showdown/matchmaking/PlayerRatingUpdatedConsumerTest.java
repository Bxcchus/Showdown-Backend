package lol.pinkward.showdown.matchmaking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lol.pinkward.showdown.contracts.PlayerRatingUpdatedPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PlayerRatingUpdatedConsumerTest {

    @Test
    void projectsTheRatingAndRefreshesAnActiveQueueEntry() throws Exception {
        PlayerRatingSnapshotRepository ratings = mock(PlayerRatingSnapshotRepository.class);
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        MatchmakingInboxRepository inbox = mock(MatchmakingInboxRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID playerId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var queued = QueueEntry.join(playerId, "EUW", "rating-test", Instant.now());
        var envelope = new PlayerRatingUpdatedEnvelope(
                eventId,
                "PLAYER_RATING_UPDATED",
                2,
                Instant.now(),
                "match-service",
                UUID.randomUUID(),
                UUID.randomUUID(),
                new PlayerRatingUpdatedPayload(playerId, 1325, 1325, 4, 28.5, 6.2));
        when(objectMapper.readValue("event", PlayerRatingUpdatedEnvelope.class)).thenReturn(envelope);
        when(ratings.findById(playerId)).thenReturn(Optional.empty());
        when(entries.findByPlayerId(playerId)).thenReturn(Optional.of(queued));
        var consumer = new PlayerRatingUpdatedConsumer(ratings, entries, inbox, objectMapper);

        consumer.consume("event");

        assertThat(queued.mmr()).isEqualTo(1325);
        assertThat(queued.skillMean()).isEqualTo(28.5);
        assertThat(queued.skillDeviation()).isEqualTo(6.2);
        verify(ratings).save(any(PlayerRatingSnapshot.class));
        verify(inbox).save(any(MatchmakingInboxEvent.class));
    }
}
