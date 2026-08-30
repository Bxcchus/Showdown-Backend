package lol.pinkward.showdown.matchmaking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import lol.pinkward.showdown.contracts.EventEnvelope;
import lol.pinkward.showdown.contracts.MatchFoundPayload;
import tools.jackson.databind.ObjectMapper;

class MatchReservationServiceTest {

    @Test
    void reservesTenPlayersAndCreatesOneOutboxEvent() throws Exception {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        List<QueueEntry> selected = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> QueueEntry.join(
                        UUID.randomUUID(),
                        "EUW",
                        "operation-" + index,
                        Instant.parse("2026-08-26T17:00:00Z").plusMillis(index)))
                .toList();
        when(entries.lockCandidates("EUW", "FIVE_V_FIVE")).thenReturn(selected);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        MatchReservationService service = new MatchReservationService(
                entries,
                outbox,
                objectMapper,
                Duration.ofSeconds(30),
                Clock.fixed(Instant.parse("2026-08-26T18:00:00Z"), ZoneOffset.UTC));

        boolean reserved = service.reserveOne("EUW");

        assertThat(reserved).isTrue();
        assertThat(selected).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo(QueueStatus.RESERVED);
            assertThat(entry.reservationId()).isNotNull();
        });
        assertThat(selected).extracting(QueueEntry::reservationId).containsOnly(selected.getFirst().reservationId());
        verify(outbox).save(any(OutboxEvent.class));
    }

    @Test
    void waitsForPrimaryRoleCoverageBeforeTheWideningDeadline() throws Exception {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Instant now = Instant.parse("2026-08-26T18:00:00Z");
        List<QueueEntry> candidates = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> QueueEntry.join(
                        UUID.randomUUID(), "EUW", "same-role-" + index,
                        now.minusSeconds(10).plusMillis(index), "JUNGLE", "MID"))
                .toList();
        when(entries.lockCandidates("EUW", "FIVE_V_FIVE")).thenReturn(candidates);
        var service = new MatchReservationService(
                entries, outbox, objectMapper, Duration.ofSeconds(30), Clock.fixed(now, ZoneOffset.UTC));

        boolean reserved = service.reserveOne("EUW");

        assertThat(reserved).isFalse();
        assertThat(candidates).allMatch(entry -> entry.status() == QueueStatus.QUEUED);
    }

    @Test
    void widensRolesOnlyAfterTheConfiguredWait() throws Exception {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Instant now = Instant.parse("2026-08-26T18:00:00Z");
        List<QueueEntry> candidates = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> QueueEntry.join(
                        UUID.randomUUID(), "EUW", "widened-" + index,
                        now.minusSeconds(31).plusMillis(index), "JUNGLE", "MID"))
                .toList();
        when(entries.lockCandidates("EUW", "FIVE_V_FIVE")).thenReturn(candidates);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        var service = new MatchReservationService(
                entries, outbox, objectMapper, Duration.ofSeconds(30), Clock.fixed(now, ZoneOffset.UTC));

        boolean reserved = service.reserveOne("EUW");

        assertThat(reserved).isTrue();
        assertThat(candidates).allMatch(entry -> entry.status() == QueueStatus.RESERVED);
    }

    @Test
    void keepsEveryPartyMemberOnTheSameTeam() throws Exception {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Instant now = Instant.parse("2026-08-26T18:00:00Z");
        UUID partyId = UUID.randomUUID();
        var partyPlayers = new java.util.ArrayList<UUID>();
        var candidates = new java.util.ArrayList<QueueEntry>();
        String[] roles = {"TOP", "JUNGLE", "MID", "BOT", "SUPPORT", "TOP", "JUNGLE", "MID", "BOT", "SUPPORT"};
        for (int index = 0; index < roles.length; index++) {
            UUID playerId = UUID.randomUUID();
            if (index < 3) partyPlayers.add(playerId);
            candidates.add(QueueEntry.join(
                    playerId,
                    index < 3 ? partyId : null,
                    "EUW",
                    "party-safe-" + index,
                    now.minusSeconds(5).plusMillis(index),
                    roles[index],
                    roles[(index + 1) % roles.length],
                    false));
        }
        when(entries.lockCandidates("EUW", "FIVE_V_FIVE")).thenReturn(candidates);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        var service = new MatchReservationService(
                entries, outbox, objectMapper, Duration.ofSeconds(30), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.reserveOne("EUW")).isTrue();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(eventCaptor.capture());
        @SuppressWarnings("unchecked")
        EventEnvelope<MatchFoundPayload> event = (EventEnvelope<MatchFoundPayload>) eventCaptor.getValue();
        List<Integer> partyIndexes = partyPlayers.stream().map(event.payload().playerIds()::indexOf).toList();
        assertThat(partyIndexes).allMatch(index -> index >= 0 && index < 5);
    }

    @Test
    void waitsForFarAwayMmrBeforeTheSearchWindowWidens() {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Instant now = Instant.parse("2026-08-26T18:00:00Z");
        when(entries.lockCandidates("EUW", "FIVE_V_FIVE")).thenReturn(ratedCandidates(now.minusSeconds(10), 1200, 1600));
        var service = new MatchReservationService(
                entries, outbox, objectMapper, Duration.ofSeconds(30),
                100, 50, Duration.ofSeconds(15), 600, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.reserveOne("EUW")).isFalse();
    }

    @Test
    void widensTheMmrWindowAndBalancesBothTeams() throws Exception {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Instant now = Instant.parse("2026-08-26T18:00:00Z");
        List<QueueEntry> candidates = ratedCandidates(now.minusSeconds(125), 1200, 1600);
        when(entries.lockCandidates("EUW", "FIVE_V_FIVE")).thenReturn(candidates);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        var service = new MatchReservationService(
                entries, outbox, objectMapper, Duration.ofSeconds(30),
                100, 50, Duration.ofSeconds(15), 600, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.reserveOne("EUW")).isTrue();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(eventCaptor.capture());
        @SuppressWarnings("unchecked")
        EventEnvelope<MatchFoundPayload> event = (EventEnvelope<MatchFoundPayload>) eventCaptor.getValue();
        var mmrByPlayer = candidates.stream().collect(java.util.stream.Collectors.toMap(
                QueueEntry::playerId, QueueEntry::mmr));
        int blueMmr = event.payload().playerIds().subList(0, 5).stream().mapToInt(mmrByPlayer::get).sum();
        int redMmr = event.payload().playerIds().subList(5, 10).stream().mapToInt(mmrByPlayer::get).sum();
        assertThat(Math.abs(blueMmr - redMmr)).isLessThanOrEqualTo(400);
        var meanByPlayer = candidates.stream().collect(java.util.stream.Collectors.toMap(
                QueueEntry::playerId, QueueEntry::skillMean));
        double blueMean = event.payload().playerIds().subList(0, 5).stream().mapToDouble(meanByPlayer::get).sum();
        double redMean = event.payload().playerIds().subList(5, 10).stream().mapToDouble(meanByPlayer::get).sum();
        assertThat(Math.abs(blueMean - redMean)).isLessThanOrEqualTo(10.0);
    }

    private static List<QueueEntry> ratedCandidates(Instant joinedAt, int lowerMmr, int upperMmr) {
        String[] roles = {"TOP", "JUNGLE", "MID", "BOT", "SUPPORT", "TOP", "JUNGLE", "MID", "BOT", "SUPPORT"};
        return java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> QueueEntry.join(
                        UUID.randomUUID(),
                        null,
                        "EUW",
                        "rated-" + UUID.randomUUID(),
                        joinedAt.plusMillis(index),
                        roles[index],
                        roles[(index + 1) % roles.length],
                        false,
                        index < 5 ? lowerMmr : upperMmr))
                .toList();
    }
}
