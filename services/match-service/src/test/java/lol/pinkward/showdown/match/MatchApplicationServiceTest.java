package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import lol.pinkward.showdown.contracts.DuelRatingUpdatedPayload;
import lol.pinkward.showdown.contracts.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class MatchApplicationServiceTest {

    @Test
    void confirmsTheMatchWhenTheTenthPlayerAccepts() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "FIVE_V_FIVE", Instant.now(), Instant.now().plusSeconds(60));
        var roster = new ArrayList<>(IntStream.range(0, 10)
                .mapToObj(index -> MatchPlayer.pending(
                        match.id(), UUID.randomUUID(), index < 5 ? TeamSide.BLUE : TeamSide.RED))
                .toList());
        roster.subList(0, 9).forEach(player -> player.answer(true));
        MatchPlayer lastPlayer = roster.getLast();
        when(matches.findById(match.id())).thenReturn(Optional.of(match));
        when(players.findByMatchIdAndPlayerId(match.id(), lastPlayer.playerId()))
                .thenReturn(Optional.of(lastPlayer));
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id())).thenReturn(roster);
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any())).thenReturn("{}");
        MatchApplicationService service = new MatchApplicationService(
                matches,
                players,
                outbox,
                ratings,
                ratingChanges,
                objectMapper,
                lobbyCredentials(),
                true);

        MatchSnapshot result = service.answer(match.id(), lastPlayer.playerId(), true);

        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.lobbyName()).startsWith("SWD-");
        assertThat(result.lobbyPassword()).hasSize(8);
        assertThat(result.players()).allMatch(player -> player.readyState().equals("ACCEPTED"));
        verify(outbox).save(org.mockito.ArgumentMatchers.any(MatchOutboxEvent.class));
    }

    @Test
    void confirmsAOneVersusOneTestMatchWhenTheHumanAccepts() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "ONE_V_ONE", Instant.now(), Instant.now().plusSeconds(60));
        MatchPlayer human = MatchPlayer.readyCheck(
                match.id(), UUID.randomUUID(), TeamSide.BLUE, false, LaneRole.MID);
        MatchPlayer bot = MatchPlayer.readyCheck(
                match.id(), UUID.randomUUID(), TeamSide.RED, true, LaneRole.MID);
        var roster = java.util.List.of(human, bot);
        when(matches.findById(match.id())).thenReturn(Optional.of(match));
        when(players.findByMatchIdAndPlayerId(match.id(), human.playerId())).thenReturn(Optional.of(human));
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id())).thenReturn(roster);
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any())).thenReturn("{}");
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, objectMapper, lobbyCredentials(), true);

        MatchSnapshot result = service.answer(match.id(), human.playerId(), true);

        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.players()).allMatch(player -> player.readyState().equals("ACCEPTED"));
        verify(outbox).save(org.mockito.ArgumentMatchers.any(MatchOutboxEvent.class));
    }

    @Test
    void cancelsTheMatchAndWritesAnOutboxEventWhenAPlayerDeclines() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "FIVE_V_FIVE", Instant.now(), Instant.now().plusSeconds(60));
        var roster = new ArrayList<>(IntStream.range(0, 10)
                .mapToObj(index -> MatchPlayer.pending(
                        match.id(), UUID.randomUUID(), index < 5 ? TeamSide.BLUE : TeamSide.RED))
                .toList());
        MatchPlayer decliningPlayer = roster.getFirst();
        when(matches.findById(match.id())).thenReturn(Optional.of(match));
        when(players.findByMatchIdAndPlayerId(match.id(), decliningPlayer.playerId()))
                .thenReturn(Optional.of(decliningPlayer));
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id())).thenReturn(roster);
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any())).thenReturn("{}");
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, objectMapper, lobbyCredentials(), true);

        MatchSnapshot result = service.answer(match.id(), decliningPlayer.playerId(), false);

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(outbox).save(org.mockito.ArgumentMatchers.any(MatchOutboxEvent.class));
    }

    @Test
    void cancelsAConfirmedTeamLobbyWithoutChangingRatings() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "FIVE_V_FIVE", Instant.now(), Instant.now().plusSeconds(60));
        match.confirm("SWD-CANCEL", "encrypted");
        MatchPlayer reporter = MatchPlayer.readyCheck(
                match.id(), UUID.randomUUID(), TeamSide.BLUE, false, LaneRole.MID);
        var roster = java.util.List.of(reporter);
        when(matches.findByIdForUpdate(match.id())).thenReturn(Optional.of(match));
        when(players.findByMatchIdAndPlayerId(match.id(), reporter.playerId())).thenReturn(Optional.of(reporter));
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id())).thenReturn(roster);
        when(objectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any())).thenReturn("{}");
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, objectMapper, lobbyCredentials(), true);

        MatchSnapshot result = service.cancelVerifiedTeamMatch(match.id(), reporter.playerId());

        assertThat(result.status()).isEqualTo("CANCELLED");
        verify(outbox).save(org.mockito.ArgumentMatchers.any(MatchOutboxEvent.class));
        verify(ratings, never()).saveAll(org.mockito.ArgumentMatchers.any());
        verify(ratingChanges, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsAResultAndRatesOnlyTheHumanPlayerOnce() {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "FIVE_V_FIVE", Instant.now(), Instant.now().plusSeconds(60));
        match.confirm("SWD-TEST", "encrypted");
        UUID playerId = UUID.randomUUID();
        MatchPlayer human = MatchPlayer.readyCheck(match.id(), playerId, TeamSide.BLUE, false, LaneRole.JUNGLE);
        var roster = new ArrayList<MatchPlayer>();
        roster.add(human);
        IntStream.range(0, 9).forEach(index -> roster.add(MatchPlayer.readyCheck(
                match.id(), UUID.randomUUID(), index < 4 ? TeamSide.BLUE : TeamSide.RED, true, LaneRole.MID)));
        when(matches.findByIdForUpdate(match.id())).thenReturn(Optional.of(match));
        when(players.findByMatchIdAndPlayerId(match.id(), playerId)).thenReturn(Optional.of(human));
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id())).thenReturn(roster);
        when(ratings.findById(playerId)).thenReturn(Optional.empty());
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, objectMapper, lobbyCredentials(), true);

        MatchSnapshot result = service.recordResult(match.id(), playerId, TeamSide.BLUE);
        MatchSnapshot repeated = service.recordResult(match.id(), playerId, TeamSide.BLUE);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.winningTeam()).isEqualTo("BLUE");
        assertThat(result.completedAt()).isNotNull();
        assertThat(repeated.winningTeam()).isEqualTo("BLUE");
        var change = org.mockito.ArgumentCaptor.forClass(RatingChange.class);
        verify(ratingChanges).save(change.capture());
        assertThat(change.getValue().ratingDelta()).isPositive();
        assertThat(change.getValue().newSkillMean()).isGreaterThan(TrueSkillCalculator.INITIAL_MEAN);
        assertThat(change.getValue().newSkillDeviation()).isLessThan(TrueSkillCalculator.INITIAL_DEVIATION);
        verify(ratings).saveAll(org.mockito.ArgumentMatchers.any());
        var ratingEvent = org.mockito.ArgumentCaptor.forClass(MatchOutboxEvent.class);
        verify(outbox).save(ratingEvent.capture());
        assertThat(ratingEvent.getValue().eventType()).isEqualTo("PLAYER_RATING_UPDATED");
    }

    @Test
    void recordsAOneVersusOneResultWithIndependentGlicko2Ratings() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        DuelPlayerRatingRepository duelRatings = mock(DuelPlayerRatingRepository.class);
        DuelRatingChangeRepository duelRatingChanges = mock(DuelRatingChangeRepository.class);
        DuelRatingSeasonService duelSeasons = mock(DuelRatingSeasonService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Instant now = Instant.now();
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "ONE_V_ONE", now, now.plusSeconds(60));
        match.confirm("SWD-DUEL", "encrypted");
        UUID winnerId = UUID.randomUUID();
        UUID loserId = UUID.randomUUID();
        MatchPlayer winner = MatchPlayer.readyCheck(match.id(), winnerId, TeamSide.BLUE, false, LaneRole.MID);
        MatchPlayer loser = MatchPlayer.readyCheck(match.id(), loserId, TeamSide.RED, false, LaneRole.MID);
        var roster = java.util.List.of(winner, loser);
        DuelPlayerRating winnerRating = DuelPlayerRating.initial(winnerId, "S1", "EUW", now);
        DuelPlayerRating loserRating = DuelPlayerRating.initial(loserId, "S1", "EUW", now);
        when(matches.findByIdForUpdate(match.id())).thenReturn(Optional.of(match));
        when(players.findByMatchIdAndPlayerId(match.id(), winnerId)).thenReturn(Optional.of(winner));
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id())).thenReturn(roster);
        when(duelSeasons.prepare(
                org.mockito.ArgumentMatchers.eq(winnerId),
                org.mockito.ArgumentMatchers.eq("EUW"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(winnerRating);
        when(duelSeasons.prepare(
                org.mockito.ArgumentMatchers.eq(loserId),
                org.mockito.ArgumentMatchers.eq("EUW"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(loserRating);
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, duelRatings, duelRatingChanges,
                objectMapper, lobbyCredentials(), duelSeasons, true);

        MatchSnapshot result = service.recordResult(match.id(), winnerId, TeamSide.BLUE);
        MatchSnapshot repeated = service.recordResult(match.id(), winnerId, TeamSide.BLUE);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(repeated.winningTeam()).isEqualTo("BLUE");
        assertThat(winnerRating.mmr()).isGreaterThan(DuelPlayerRating.INITIAL_MMR);
        assertThat(loserRating.mmr()).isLessThan(DuelPlayerRating.INITIAL_MMR);
        assertThat(winnerRating.ratingDeviation()).isLessThan(Glicko2Calculator.INITIAL_DEVIATION);
        var changes = org.mockito.ArgumentCaptor.forClass(DuelRatingChange.class);
        verify(duelRatingChanges, times(2)).save(changes.capture());
        assertThat(changes.getAllValues())
                .anyMatch(change -> change.playerId().equals(winnerId) && change.ratingDelta() > 0)
                .anyMatch(change -> change.playerId().equals(loserId) && change.ratingDelta() < 0);
        verify(duelRatings).saveAll(org.mockito.ArgumentMatchers.any());
        verify(ratingChanges, never()).save(org.mockito.ArgumentMatchers.any());
        var events = org.mockito.ArgumentCaptor.forClass(MatchOutboxEvent.class);
        verify(outbox, times(2)).save(events.capture());
        assertThat(events.getAllValues()).allSatisfy(event -> {
            assertThat(event.eventType()).isEqualTo(MatchApplicationService.DUEL_RATING_UPDATED);
            assertThat(event.routingKey()).isEqualTo(MatchApplicationService.DUEL_RATING_UPDATED_KEY);
        });
        var serialized = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(objectMapper, times(2)).writeValueAsString(serialized.capture());
        var payloads = serialized.getAllValues().stream().map(value -> {
            assertThat(value).isInstanceOf(EventEnvelope.class);
            EventEnvelope<?> envelope = (EventEnvelope<?>) value;
            assertThat(envelope.eventType()).isEqualTo(MatchApplicationService.DUEL_RATING_UPDATED);
            assertThat(envelope.eventVersion()).isEqualTo(1);
            assertThat(envelope.correlationId()).isEqualTo(match.id());
            assertThat(envelope.causationId()).isEqualTo(match.id());
            assertThat(envelope.payload()).isInstanceOf(DuelRatingUpdatedPayload.class);
            return (DuelRatingUpdatedPayload) envelope.payload();
        }).toList();
        assertThat(payloads).extracting(DuelRatingUpdatedPayload::playerId)
                .containsExactlyInAnyOrder(winnerId, loserId);
        assertThat(payloads).anySatisfy(payload -> {
            assertThat(payload.playerId()).isEqualTo(winnerId);
            assertThat(payload.mmr()).isEqualTo(winnerRating.mmr());
            assertThat(payload.games()).isEqualTo(1);
            assertThat(payload.season()).isEqualTo("S1");
            assertThat(payload.region()).isEqualTo("EUW");
        });
    }

    @Test
    void recordsARatedBotDuelForTheHumanThroughTheDedicatedEndpoint() throws Exception {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        DuelPlayerRatingRepository duelRatings = mock(DuelPlayerRatingRepository.class);
        DuelRatingChangeRepository duelRatingChanges = mock(DuelRatingChangeRepository.class);
        DuelRatingSeasonService duelSeasons = mock(DuelRatingSeasonService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Instant now = Instant.now();
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "ONE_V_ONE", now, now.plusSeconds(60));
        match.confirm("SWD-BOT", "encrypted");
        MatchPlayer human = MatchPlayer.readyCheck(
                match.id(), UUID.randomUUID(), TeamSide.BLUE, false, LaneRole.MID);
        MatchPlayer bot = MatchPlayer.readyCheck(
                match.id(), UUID.randomUUID(), TeamSide.RED, true, LaneRole.MID);
        var roster = java.util.List.of(human, bot);
        when(matches.findById(match.id())).thenReturn(Optional.of(match));
        when(matches.findByIdForUpdate(match.id())).thenReturn(Optional.of(match));
        when(players.findByMatchIdAndPlayerId(match.id(), human.playerId())).thenReturn(Optional.of(human));
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id())).thenReturn(roster);
        DuelPlayerRating humanRating = DuelPlayerRating.initial(human.playerId(), "S1", "EUW", now);
        when(duelSeasons.prepare(
                org.mockito.ArgumentMatchers.eq(human.playerId()),
                org.mockito.ArgumentMatchers.eq("EUW"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(humanRating);
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, duelRatings, duelRatingChanges,
                objectMapper, lobbyCredentials(), duelSeasons, false);

        MatchSnapshot result = service.recordTrustedResult(match.id(), TeamSide.BLUE);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.winningTeam()).isEqualTo("BLUE");
        assertThat(humanRating.mmr()).isGreaterThan(DuelPlayerRating.INITIAL_MMR);
        assertThat(humanRating.games()).isEqualTo(1);
        assertThat(humanRating.wins()).isEqualTo(1);
        var change = org.mockito.ArgumentCaptor.forClass(DuelRatingChange.class);
        verify(duelRatingChanges).save(change.capture());
        assertThat(change.getValue().playerId()).isEqualTo(human.playerId());
        assertThat(change.getValue().ratingDelta()).isPositive();
        verify(duelRatings).save(humanRating);
        verify(ratings, never()).saveAll(org.mockito.ArgumentMatchers.any());
        verify(ratingChanges, never()).save(org.mockito.ArgumentMatchers.any());
        var event = org.mockito.ArgumentCaptor.forClass(MatchOutboxEvent.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(MatchApplicationService.DUEL_RATING_UPDATED);
        assertThat(event.getValue().routingKey()).isEqualTo(MatchApplicationService.DUEL_RATING_UPDATED_KEY);
        var serialized = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(objectMapper).writeValueAsString(serialized.capture());
        assertThat(serialized.getValue()).isInstanceOf(EventEnvelope.class);
        EventEnvelope<?> envelope = (EventEnvelope<?>) serialized.getValue();
        assertThat(envelope.eventType()).isEqualTo(MatchApplicationService.DUEL_RATING_UPDATED);
        assertThat(envelope.payload()).isInstanceOf(DuelRatingUpdatedPayload.class);
        DuelRatingUpdatedPayload payload = (DuelRatingUpdatedPayload) envelope.payload();
        assertThat(payload.playerId()).isEqualTo(human.playerId());
        assertThat(payload.mmr()).isEqualTo(humanRating.mmr());
        assertThat(payload.peakMmr()).isEqualTo(humanRating.peakRating());
        assertThat(payload.games()).isEqualTo(1);
        assertThat(payload.season()).isEqualTo("S1");
        assertThat(payload.region()).isEqualTo("EUW");
    }

    @Test
    void reportsTheFullTeamLeaderboardAndTheViewerOutsideTheRequestedSlice() {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        DuelPlayerRatingRepository duelRatings = mock(DuelPlayerRatingRepository.class);
        DuelRatingChangeRepository duelRatingChanges = mock(DuelRatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RatingSeasonService seasons = mock(RatingSeasonService.class);
        DuelRatingSeasonService duelSeasons = mock(DuelRatingSeasonService.class);
        Instant startsAt = Instant.parse("2026-01-01T00:00:00Z");
        RatingSeason season = RatingSeason.of(
                "S1", startsAt, startsAt.plusSeconds(31_536_000), 1, 0.5);
        Instant now = startsAt.plusSeconds(60);
        PlayerRating first = PlayerRating.initial(UUID.randomUUID(), "S1", "EUW", now);
        PlayerRating second = PlayerRating.initial(UUID.randomUUID(), "S1", "EUW", now.plusSeconds(1));
        UUID viewerId = UUID.randomUUID();
        PlayerRating viewer = PlayerRating.initial(viewerId, "S1", "EUW", now.plusSeconds(2));
        first.apply(true, new TrueSkillCalculator.Skill(30.0, 7.0), now.plusSeconds(3));
        second.apply(true, new TrueSkillCalculator.Skill(28.0, 7.5), now.plusSeconds(4));
        viewer.apply(false, new TrueSkillCalculator.Skill(23.0, 8.0), now.plusSeconds(5));
        when(seasons.current(org.mockito.ArgumentMatchers.any())).thenReturn(season);
        when(ratings.findBySeasonCodeAndRegionAndGamesGreaterThanEqualOrderByRatingDescUpdatedAtAscPlayerIdAsc(
                "S1", "EUW", 1)).thenReturn(java.util.List.of(first, second, viewer));
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, duelRatings, duelRatingChanges,
                objectMapper, lobbyCredentials(), seasons, duelSeasons, null, true);

        LeaderboardSnapshot result = service.leaderboard(viewerId, "EUW", 2);

        assertThat(result.totalEntries()).isEqualTo(3);
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries()).extracting(LeaderboardEntry::playerId)
                .containsExactly(first.playerId(), second.playerId());
        assertThat(result.viewer()).isNotNull();
        assertThat(result.viewer().playerId()).isEqualTo(viewerId);
        assertThat(result.viewer().position()).isEqualTo(3);
    }

    @Test
    void reportsTheFullDuelLeaderboardAndTheViewerOutsideTheRequestedSlice() {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        DuelPlayerRatingRepository duelRatings = mock(DuelPlayerRatingRepository.class);
        DuelRatingChangeRepository duelRatingChanges = mock(DuelRatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        RatingSeasonService seasons = mock(RatingSeasonService.class);
        DuelRatingSeasonService duelSeasons = mock(DuelRatingSeasonService.class);
        Instant startsAt = Instant.parse("2026-01-01T00:00:00Z");
        RatingSeason season = RatingSeason.of(
                "S1", startsAt, startsAt.plusSeconds(31_536_000), 1, 0.5);
        Instant now = startsAt.plusSeconds(60);
        DuelPlayerRating first = DuelPlayerRating.initial(UUID.randomUUID(), "S1", "EUW", now);
        DuelPlayerRating second = DuelPlayerRating.initial(UUID.randomUUID(), "S1", "EUW", now.plusSeconds(1));
        UUID viewerId = UUID.randomUUID();
        DuelPlayerRating viewer = DuelPlayerRating.initial(viewerId, "S1", "EUW", now.plusSeconds(2));
        first.apply(true, new Glicko2Calculator.Rating(1700.0, 120.0, 0.06), now.plusSeconds(3));
        second.apply(true, new Glicko2Calculator.Rating(1600.0, 130.0, 0.06), now.plusSeconds(4));
        viewer.apply(false, new Glicko2Calculator.Rating(1450.0, 140.0, 0.06), now.plusSeconds(5));
        when(duelSeasons.current(org.mockito.ArgumentMatchers.any())).thenReturn(season);
        when(duelRatings.findBySeasonCodeAndRegionAndGamesGreaterThanEqualOrderByRatingDescUpdatedAtAscPlayerIdAsc(
                "S1", "EUW", 1)).thenReturn(java.util.List.of(first, second, viewer));
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, duelRatings, duelRatingChanges,
                objectMapper, lobbyCredentials(), seasons, duelSeasons, null, true);

        DuelLeaderboardSnapshot result = service.duelLeaderboard(viewerId, "EUW", 2);

        assertThat(result.totalEntries()).isEqualTo(3);
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries()).extracting(DuelLeaderboardEntry::playerId)
                .containsExactly(first.playerId(), second.playerId());
        assertThat(result.viewer()).isNotNull();
        assertThat(result.viewer().playerId()).isEqualTo(viewerId);
        assertThat(result.viewer().position()).isEqualTo(3);
    }

    @Test
    void loadsOnlyTheRatingChangesForTheRequestedHistoryPageWithATotalOrder() {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        Instant now = Instant.parse("2026-08-28T12:00:00Z");
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "FIVE_V_FIVE", now.minusSeconds(120), now.minusSeconds(60));
        match.confirm("SWD-HISTORY", "encrypted");
        match.recordResult(TeamSide.BLUE, now);
        UUID playerId = UUID.randomUUID();
        MatchPlayer viewer = MatchPlayer.readyCheck(
                match.id(), playerId, TeamSide.BLUE, false, LaneRole.JUNGLE);
        when(players.findByMatchIdAndPlayerId(match.id(), playerId)).thenReturn(Optional.of(viewer));
        when(matches.findHistoryForPlayer(
                org.mockito.ArgumentMatchers.eq(playerId),
                org.mockito.ArgumentMatchers.eq("EUW"),
                org.mockito.ArgumentMatchers.eq("FIVE_V_FIVE"),
                org.mockito.ArgumentMatchers.eq("JUNGLE"),
                org.mockito.ArgumentMatchers.eq("VICTORY"),
                org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(match)));
        when(ratingChanges.findByPlayerIdAndMatchIdInOrderByCreatedAtDesc(
                playerId, java.util.List.of(match.id())))
                .thenReturn(java.util.List.of());
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, objectMapper, lobbyCredentials(), true);

        MatchHistoryPage result = service.history(
                playerId, 0, 25, "EUW", "FIVE_V_FIVE", LaneRole.JUNGLE, "VICTORY");

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst().matchId()).isEqualTo(match.id());
        assertThat(result.content().getFirst().outcome()).isEqualTo("VICTORY");
        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(matches).findHistoryForPlayer(
                org.mockito.ArgumentMatchers.eq(playerId),
                org.mockito.ArgumentMatchers.eq("EUW"),
                org.mockito.ArgumentMatchers.eq("FIVE_V_FIVE"),
                org.mockito.ArgumentMatchers.eq("JUNGLE"),
                org.mockito.ArgumentMatchers.eq("VICTORY"),
                pageable.capture());
        assertThat(pageable.getValue().getSort().stream().map(order -> order.getProperty()).toList())
                .containsExactly("completed_at", "id");
        verify(ratingChanges).findByPlayerIdAndMatchIdInOrderByCreatedAtDesc(
                playerId, java.util.List.of(match.id()));
    }

    @Test
    void hidesACompletedMatchDetailFromPlayersOutsideTheMatch() {
        GameMatchRepository matches = mock(GameMatchRepository.class);
        MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        MatchOutboxRepository outbox = mock(MatchOutboxRepository.class);
        PlayerRatingRepository ratings = mock(PlayerRatingRepository.class);
        RatingChangeRepository ratingChanges = mock(RatingChangeRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        UUID matchId = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        when(matches.findCompletedForPlayer(matchId, outsider)).thenReturn(Optional.empty());
        MatchApplicationService service = new MatchApplicationService(
                matches, players, outbox, ratings, ratingChanges, objectMapper, lobbyCredentials(), true);

        ResponseStatusException error = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> service.detail(matchId, outsider));

        assertThat(error.getStatusCode().value()).isEqualTo(404);
        verify(players, never()).findByMatchIdAndPlayerId(matchId, outsider);
    }

    private static LobbyCredentialService lobbyCredentials() {
        return new LobbyCredentialService("test-lobby-credential-key-change-me");
    }
}
