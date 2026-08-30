package lol.pinkward.showdown.matchmaking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lol.pinkward.showdown.contracts.DuelRatingUpdatedPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DuelRatingUpdatedConsumerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-29T10:00:00Z");

    @Test
    void refreshesAQueuedOneVersusOneEntryInTheSameRegion() throws Exception {
        Fixture fixture = fixture("ONE_V_ONE", "EUW", "EUW");

        fixture.consumer().consume("event");

        assertThat(fixture.queued().mmr()).isEqualTo(1715);
        assertThat(fixture.queued().skillMean()).isEqualTo(25.0 + (1714.8 - 1500.0) / 40.0);
        assertThat(fixture.queued().skillDeviation()).isEqualTo(2.75);
        verify(fixture.ratings()).save(any(DuelRatingSnapshot.class));
        verify(fixture.inbox()).save(any(MatchmakingInboxEvent.class));
    }

    @Test
    void doesNotRefreshAFiveVersusFiveEntry() throws Exception {
        Fixture fixture = fixture("FIVE_V_FIVE", "EUW", "EUW");
        int initialMmr = fixture.queued().mmr();
        double initialMean = fixture.queued().skillMean();
        double initialDeviation = fixture.queued().skillDeviation();

        fixture.consumer().consume("event");

        assertThat(fixture.queued().mmr()).isEqualTo(initialMmr);
        assertThat(fixture.queued().skillMean()).isEqualTo(initialMean);
        assertThat(fixture.queued().skillDeviation()).isEqualTo(initialDeviation);
    }

    @Test
    void doesNotRefreshAOneVersusOneEntryFromAnotherRegion() throws Exception {
        Fixture fixture = fixture("ONE_V_ONE", "EUW", "NA");
        int initialMmr = fixture.queued().mmr();
        double initialMean = fixture.queued().skillMean();
        double initialDeviation = fixture.queued().skillDeviation();

        fixture.consumer().consume("event");

        assertThat(fixture.queued().mmr()).isEqualTo(initialMmr);
        assertThat(fixture.queued().skillMean()).isEqualTo(initialMean);
        assertThat(fixture.queued().skillDeviation()).isEqualTo(initialDeviation);
    }

    private static Fixture fixture(String mode, String queueRegion, String eventRegion) throws Exception {
        DuelRatingSnapshotRepository ratings = mock(DuelRatingSnapshotRepository.class);
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        MatchmakingInboxRepository inbox = mock(MatchmakingInboxRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID playerId = UUID.randomUUID();
        var queued = QueueEntry.joinSolo(
                playerId,
                queueRegion,
                mode,
                "rating-test",
                OCCURRED_AT.minusSeconds(120),
                "MID",
                "JUNGLE",
                false,
                1500,
                25.0,
                DuelRatingSnapshot.INITIAL_RATING_DEVIATION / 40.0);
        var payload = new DuelRatingUpdatedPayload(
                playerId,
                1715,
                1740,
                9,
                "S2026",
                eventRegion,
                1714.8,
                110.0,
                0.06);
        var envelope = new DuelRatingUpdatedEnvelope(
                UUID.randomUUID(),
                "DUEL_RATING_UPDATED",
                1,
                OCCURRED_AT,
                "match-service",
                UUID.randomUUID(),
                UUID.randomUUID(),
                payload);
        when(objectMapper.readValue("event", DuelRatingUpdatedEnvelope.class)).thenReturn(envelope);
        when(ratings.findById(playerId)).thenReturn(Optional.empty());
        when(entries.findByPlayerId(playerId)).thenReturn(Optional.of(queued));
        var consumer = new DuelRatingUpdatedConsumer(ratings, entries, inbox, objectMapper);
        return new Fixture(consumer, ratings, inbox, queued);
    }

    private record Fixture(
            DuelRatingUpdatedConsumer consumer,
            DuelRatingSnapshotRepository ratings,
            MatchmakingInboxRepository inbox,
            QueueEntry queued) {}
}
