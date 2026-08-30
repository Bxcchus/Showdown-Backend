package lol.pinkward.showdown.matchmaking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lol.pinkward.showdown.contracts.DuelRatingUpdatedPayload;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QueueServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T10:00:00Z");

    @Test
    void oneVersusOneUsesTheRegionalGlickoProjection() {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        PlayerRatingSnapshotRepository trueSkillRatings = mock(PlayerRatingSnapshotRepository.class);
        DuelRatingSnapshotRepository glickoRatings = mock(DuelRatingSnapshotRepository.class);
        UUID playerId = UUID.randomUUID();
        var duelRating = DuelRatingSnapshot.create(new DuelRatingUpdatedPayload(
                playerId,
                1732,
                1780,
                12,
                "S2026",
                "EUW",
                1731.6,
                120.0,
                0.06), NOW.minusSeconds(60));
        when(entries.findByPlayerId(playerId)).thenReturn(Optional.empty());
        when(glickoRatings.findById(playerId)).thenReturn(Optional.of(duelRating));
        when(entries.save(any(QueueEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new QueueService(
                entries,
                trueSkillRatings,
                glickoRatings,
                Clock.fixed(NOW, ZoneOffset.UTC));

        QueueSnapshot result = service.join(
                playerId, "euw", "ONE_V_ONE", "MID", "JUNGLE", "duel-operation");

        var savedEntry = ArgumentCaptor.forClass(QueueEntry.class);
        verify(entries).save(savedEntry.capture());
        assertThat(result.mode()).isEqualTo("ONE_V_ONE");
        assertThat(result.mmr()).isEqualTo(1732);
        assertThat(savedEntry.getValue().skillMean()).isEqualTo(25.0 + (1731.6 - 1500.0) / 40.0);
        assertThat(savedEntry.getValue().skillDeviation()).isEqualTo(3.0);
        verify(glickoRatings).findById(playerId);
        verifyNoInteractions(trueSkillRatings);
    }

    @Test
    void fiveVersusFiveKeepsUsingTheTrueSkillProjection() {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        PlayerRatingSnapshotRepository trueSkillRatings = mock(PlayerRatingSnapshotRepository.class);
        DuelRatingSnapshotRepository glickoRatings = mock(DuelRatingSnapshotRepository.class);
        UUID playerId = UUID.randomUUID();
        var trueSkillRating = PlayerRatingSnapshot.create(
                playerId, 1384, 1410, 8, 29.4, 5.7, NOW.minusSeconds(60));
        when(entries.findByPlayerId(playerId)).thenReturn(Optional.empty());
        when(trueSkillRatings.findById(playerId)).thenReturn(Optional.of(trueSkillRating));
        when(entries.save(any(QueueEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new QueueService(
                entries,
                trueSkillRatings,
                glickoRatings,
                Clock.fixed(NOW, ZoneOffset.UTC));

        QueueSnapshot result = service.join(
                playerId, "EUW", "FIVE_V_FIVE", "JUNGLE", "MID", "team-operation");

        var savedEntry = ArgumentCaptor.forClass(QueueEntry.class);
        verify(entries).save(savedEntry.capture());
        assertThat(result.mode()).isEqualTo("FIVE_V_FIVE");
        assertThat(result.mmr()).isEqualTo(1384);
        assertThat(savedEntry.getValue().skillMean()).isEqualTo(29.4);
        assertThat(savedEntry.getValue().skillDeviation()).isEqualTo(5.7);
        verify(trueSkillRatings).findById(playerId);
        verifyNoInteractions(glickoRatings);
    }
}
