package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BotMatchResultServiceTest {

    private final GameMatchRepository matches = mock(GameMatchRepository.class);
    private final MatchPlayerRepository players = mock(MatchPlayerRepository.class);
    private final LobbyCredentialService credentials =
            new LobbyCredentialService("test-lobby-credential-key-change-me");
    private final MatchApplicationService matchService = mock(MatchApplicationService.class);

    @Test
    void derivesTheWinningTeamFromTheServerRoster() {
        Fixture fixture = botDuel(TeamSide.RED, TeamSide.BLUE);
        BotMatchResultService service = service(true);
        MatchSnapshot expected = mock(MatchSnapshot.class);
        when(matchService.recordTrustedResult(fixture.match().id(), TeamSide.RED)).thenReturn(expected);

        MatchSnapshot result = service.record(
                fixture.match().id(),
                new BotMatchResultRequest(true, DuelObjective.FIRST_BLOOD, Instant.now()));

        assertThat(result).isSameAs(expected);
        verify(matchService).recordTrustedResult(fixture.match().id(), TeamSide.RED);
    }

    @Test
    void derivesTheBotTeamWhenTheHumanLoses() {
        Fixture fixture = botDuel(TeamSide.BLUE, TeamSide.RED);
        BotMatchResultService service = service(true);
        when(matchService.recordTrustedResult(fixture.match().id(), TeamSide.RED))
                .thenReturn(mock(MatchSnapshot.class));

        service.record(
                fixture.match().id(),
                new BotMatchResultRequest(false, DuelObjective.FIRST_TO_100_CS, Instant.now()));

        verify(matchService).recordTrustedResult(fixture.match().id(), TeamSide.RED);
    }

    @Test
    void refusesLocalBotIngestionUnlessExplicitlyEnabled() {
        Fixture fixture = botDuel(TeamSide.BLUE, TeamSide.RED);

        assertThatThrownBy(() -> service(false).assignment(fixture.match().id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void refusesAnyRosterOtherThanOneHumanAndOneBot() {
        Fixture fixture = botDuel(TeamSide.BLUE, TeamSide.RED);
        MatchPlayer secondHuman = MatchPlayer.readyCheck(
                fixture.match().id(), UUID.randomUUID(), TeamSide.RED, false, LaneRole.MID);
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(fixture.match().id()))
                .thenReturn(List.of(fixture.human(), secondHuman));

        assertThatThrownBy(() -> service(true).record(
                fixture.match().id(),
                new BotMatchResultRequest(true, DuelObjective.FIRST_TOWER, Instant.now())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("one human and one bot");
    }

    @Test
    void refusesAStaleBotObservation() {
        Fixture fixture = botDuel(TeamSide.BLUE, TeamSide.RED);

        assertThatThrownBy(() -> service(true).record(
                fixture.match().id(),
                new BotMatchResultRequest(
                        true, DuelObjective.FIRST_BLOOD, Instant.now().minusSeconds(180))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("time window");
    }

    private BotMatchResultService service(boolean enabled) {
        return new BotMatchResultService(matches, players, credentials, matchService, enabled);
    }

    private Fixture botDuel(TeamSide humanTeam, TeamSide botTeam) {
        Instant now = Instant.now();
        GameMatch match = GameMatch.readyCheck(
                UUID.randomUUID(), "EUW", "ONE_V_ONE", now, now.plusSeconds(60));
        LobbyCredentialService.LobbyCredentials lobby = credentials.create(match.id());
        match.confirm(lobby.name(), lobby.encryptedPassword());
        MatchPlayer human = MatchPlayer.readyCheck(
                match.id(), UUID.randomUUID(), humanTeam, false, LaneRole.MID);
        MatchPlayer bot = MatchPlayer.readyCheck(
                match.id(), UUID.randomUUID(), botTeam, true, LaneRole.MID);
        when(matches.findById(match.id())).thenReturn(Optional.of(match));
        when(players.findByMatchIdOrderByTeamAscPlayerIdAsc(match.id()))
                .thenReturn(List.of(human, bot));
        return new Fixture(match, human, bot);
    }

    private record Fixture(GameMatch match, MatchPlayer human, MatchPlayer bot) {}
}
