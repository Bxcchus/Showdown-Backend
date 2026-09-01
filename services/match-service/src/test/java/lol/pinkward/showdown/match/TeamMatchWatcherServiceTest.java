package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TeamMatchWatcherServiceTest {
    private static final Instant NOW = Instant.now();

    @Test
    void assignmentContainsTheAuthoritativeTenPlayerRoster() {
        Fixture fixture = new Fixture();
        String rawToken = "team-watcher-assignment";
        DuelWatcherToken token = fixture.token(fixture.bluePlayers.getFirst(), rawToken, "HOST");
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));
        when(fixture.credentials.decrypt("encrypted-password")).thenReturn("secret-lobby");
        fixture.stubIdentities();

        TeamWatcherAssignment assignment = fixture.service.assignment(rawToken, fixture.match.id());

        assertThat(assignment.players()).hasSize(10);
        assertThat(assignment.players()).allMatch(player -> !player.bot() && player.puuid() != null);
        assertThat(assignment.ownTeam()).isEqualTo("BLUE");
    }

    @Test
    void requiresMatchingReportsFromBothHumanTeamsBeforeRecordingTheResult() {
        Fixture fixture = new Fixture();
        UUID blue = fixture.bluePlayers.getFirst();
        UUID red = fixture.redPlayers.getFirst();
        String rawToken = "team-watcher-red-result";
        DuelWatcherToken token = fixture.token(red, rawToken, "GUEST");
        TeamMatchWatcherResult blueReport = TeamMatchWatcherResult.observed(
                fixture.match.id(), blue, TeamSide.BLUE, TeamSide.BLUE, "123456", NOW, NOW);
        TeamMatchWatcherResult redReport = TeamMatchWatcherResult.observed(
                fixture.match.id(), red, TeamSide.RED, TeamSide.BLUE, "123456", NOW, NOW);
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));
        when(fixture.results.findByMatchIdAndReporterId(fixture.match.id(), red)).thenReturn(Optional.of(redReport));
        when(fixture.results.findAllByMatchId(fixture.match.id())).thenReturn(List.of(blueReport, redReport));
        fixture.stubIdentities();
        TeamWatcherResultRequest request = new TeamWatcherResultRequest(
                TeamSide.BLUE, "123456", fixture.puuids(), fixture.champions("Jinx"), NOW);

        TeamWatcherResultResponse response = fixture.service.observeResult(rawToken, fixture.match.id(), request);

        assertThat(response.status()).isEqualTo("VERIFIED");
        verify(fixture.matchService).recordVerifiedTeamResult(fixture.match.id(), red, TeamSide.BLUE);
        verify(fixture.tokens).findAllByMatchId(fixture.match.id());
        assertThat(fixture.roster).allMatch(player -> "Jinx".equals(player.championName()));
    }

    @Test
    void contradictoryReportsRequireReviewAndNeverChangeMmr() {
        Fixture fixture = new Fixture();
        UUID blue = fixture.bluePlayers.getFirst();
        UUID red = fixture.redPlayers.getFirst();
        String rawToken = "team-watcher-conflict";
        DuelWatcherToken token = fixture.token(red, rawToken, "GUEST");
        TeamMatchWatcherResult blueReport = TeamMatchWatcherResult.observed(
                fixture.match.id(), blue, TeamSide.BLUE, TeamSide.BLUE, "same-game", NOW, NOW);
        TeamMatchWatcherResult redReport = TeamMatchWatcherResult.observed(
                fixture.match.id(), red, TeamSide.RED, TeamSide.RED, "same-game", NOW, NOW);
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));
        when(fixture.results.findByMatchIdAndReporterId(fixture.match.id(), red)).thenReturn(Optional.of(redReport));
        when(fixture.results.findAllByMatchId(fixture.match.id())).thenReturn(List.of(blueReport, redReport));
        when(fixture.tokens.findAllByMatchId(fixture.match.id())).thenReturn(List.of(token));
        fixture.stubIdentities();

        TeamWatcherResultResponse response = fixture.service.observeResult(
                rawToken, fixture.match.id(),
                new TeamWatcherResultRequest(
                        TeamSide.RED, "same-game", fixture.puuids(), fixture.champions("Ahri"), NOW));

        assertThat(response.status()).isEqualTo("REVIEW_REQUIRED");
        verify(fixture.matchService, never()).recordVerifiedTeamResult(any(), any(), any());
    }

    @Test
    void cancelsAConfirmedLobbyWhenChampSelectIsAborted() {
        Fixture fixture = new Fixture();
        UUID reporter = fixture.bluePlayers.getFirst();
        String rawToken = "team-watcher-cancel";
        DuelWatcherToken token = fixture.token(reporter, rawToken, "HOST");
        token.touch("CHAMP_SELECT_STARTED", NOW);
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));
        when(fixture.tokens.findAllByMatchId(fixture.match.id())).thenReturn(List.of(token));

        fixture.service.cancel(rawToken, fixture.match.id());

        verify(fixture.matchService).cancelVerifiedTeamMatch(fixture.match.id(), reporter);
        assertThat(token.active(NOW.plusSeconds(1))).isFalse();
    }

    @Test
    void refusesToCancelAfterTheGameStarted() {
        Fixture fixture = new Fixture();
        UUID reporter = fixture.bluePlayers.getFirst();
        String rawToken = "team-watcher-in-game-cancel";
        DuelWatcherToken token = fixture.token(reporter, rawToken, "HOST");
        token.touch("IN_GAME", NOW);
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> fixture.service.cancel(rawToken, fixture.match.id()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");

        verify(fixture.matchService, never()).cancelVerifiedTeamMatch(any(), any());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class Fixture {
        final DuelWatcherTokenRepository tokens = mock(DuelWatcherTokenRepository.class);
        final TeamMatchWatcherResultRepository results = mock(TeamMatchWatcherResultRepository.class);
        final GameMatchRepository matches = mock(GameMatchRepository.class);
        final MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        final LobbyCredentialService credentials = mock(LobbyCredentialService.class);
        final MatchApplicationService matchService = mock(MatchApplicationService.class);
        final MatchRealtimeHub realtime = mock(MatchRealtimeHub.class);
        final DuelIdentityClient identities = mock(DuelIdentityClient.class);
        final GameMatch match;
        final List<UUID> bluePlayers = new ArrayList<>();
        final List<UUID> redPlayers = new ArrayList<>();
        final List<MatchPlayer> roster = new ArrayList<>();
        final Map<UUID, String> puuids = new HashMap<>();
        final TeamMatchWatcherService service;

        Fixture() {
            match = GameMatch.readyCheck(UUID.randomUUID(), "EUW", "FIVE_V_FIVE", NOW, NOW.plusSeconds(30));
            match.confirm("SWD-TEAM", "encrypted-password");
            LaneRole[] roles = LaneRole.values();
            for (int index = 0; index < 5; index++) {
                UUID blue = UUID.randomUUID();
                UUID red = UUID.randomUUID();
                bluePlayers.add(blue);
                redPlayers.add(red);
                roster.add(MatchPlayer.readyCheck(match.id(), blue, TeamSide.BLUE, false, roles[index]));
                roster.add(MatchPlayer.readyCheck(match.id(), red, TeamSide.RED, false, roles[index]));
                puuids.put(blue, "blue-puuid-0000000" + index);
                puuids.put(red, "red-puuid-00000000" + index);
            }
            roster.sort(Comparator.comparing(player -> player.team().name() + player.playerId()));
            when(matches.findById(match.id())).thenReturn(Optional.of(match));
            when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id())).thenReturn(roster);
            for (MatchPlayer player : roster) {
                when(players.findByMatchIdAndPlayerId(match.id(), player.playerId())).thenReturn(Optional.of(player));
            }
            service = new TeamMatchWatcherService(tokens, results, matches, players, credentials,
                    matchService, realtime, identities, Duration.ofHours(4));
        }

        DuelWatcherToken token(UUID playerId, String raw, String role) {
            return DuelWatcherToken.issue(match.id(), playerId, sha256(raw), role, NOW, NOW.plusSeconds(600));
        }

        void stubIdentities() {
            for (MatchPlayer player : roster) {
                String puuid = puuids.get(player.playerId());
                when(identities.resolve(player.playerId())).thenReturn(new DuelIdentityClient.DuelIdentity(
                        player.playerId(), puuid, "Player" + player.playerId().toString().substring(0, 6) + "#EUW",
                        "EUW"));
            }
        }

        List<String> puuids() {
            return roster.stream().map(player -> puuids.get(player.playerId())).toList();
        }

        List<TeamWatcherChampion> champions(String championName) {
            return roster.stream()
                    .map(player -> new TeamWatcherChampion(puuids.get(player.playerId()), championName))
                    .toList();
        }
    }
}
