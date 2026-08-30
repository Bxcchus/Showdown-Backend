package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DuelWatcherServiceTest {

    private static final Instant NOW = Instant.now();

    @Test
    void refusesToIssueATokenToAPlayerOutsideTheDuel() {
        Fixture fixture = new Fixture();
        UUID intruder = UUID.randomUUID();
        when(fixture.matches.findById(fixture.match.id())).thenReturn(Optional.of(fixture.match));
        when(fixture.players.findByMatchIdAndPlayerId(fixture.match.id(), intruder))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.service.issue(fixture.match.id(), intruder))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not in this duel");
    }

    @Test
    void refusesAValidWatcherTokenOnAnotherMatch() {
        Fixture fixture = new Fixture();
        String rawToken = "watcher-token-for-match-one";
        DuelWatcherToken token = DuelWatcherToken.issue(
                fixture.match.id(), fixture.host, sha256(rawToken), "HOST", NOW, NOW.plusSeconds(120));
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> fixture.service.assignment(rawToken, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not belong to this duel");
    }

    @Test
    void refusesAnExpiredWatcherToken() {
        Fixture fixture = new Fixture();
        String rawToken = "expired-watcher-token";
        DuelWatcherToken token = DuelWatcherToken.issue(
                fixture.match.id(), fixture.host, sha256(rawToken), "HOST",
                NOW.minusSeconds(300), NOW.minusSeconds(1));
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> fixture.service.assignment(rawToken, fixture.match.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void refusesARevokedWatcherToken() {
        Fixture fixture = new Fixture();
        String rawToken = "revoked-watcher-token";
        DuelWatcherToken token = DuelWatcherToken.issue(
                fixture.match.id(), fixture.host, sha256(rawToken), "HOST", NOW, NOW.plusSeconds(120));
        token.revoke(NOW);
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> fixture.service.assignment(rawToken, fixture.match.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void replayingTheSameWatcherObservationDoesNotCreateASecondConfirmation() {
        Fixture fixture = new Fixture();
        String rawToken = "watcher-token-replayed";
        DuelWatcherToken token = DuelWatcherToken.issue(
                fixture.match.id(), fixture.host, sha256(rawToken), "HOST", NOW, NOW.plusSeconds(120));
        DuelWatcherObservation prior = DuelWatcherObservation.observed(
                fixture.match.id(), fixture.host, DuelObjective.FIRST_BLOOD,
                fixture.host, NOW, NOW);
        when(fixture.tokens.findByTokenHash(sha256(rawToken))).thenReturn(Optional.of(token));
        when(fixture.challenges.findByMatchId(fixture.match.id()))
                .thenReturn(Optional.of(fixture.challenge));
        when(fixture.observations.findByMatchIdAndReporterIdAndObjective(
                fixture.match.id(), fixture.host, DuelObjective.FIRST_BLOOD))
                .thenReturn(Optional.of(prior));
        when(fixture.observations.countByMatchIdAndObjectiveAndWinnerPlayerId(
                fixture.match.id(), DuelObjective.FIRST_BLOOD, fixture.host)).thenReturn(1L);
        WatcherObservationRequest request = new WatcherObservationRequest(
                DuelObjective.FIRST_BLOOD, "Claude Code#JAVA", NOW);

        assertThat(fixture.service.observe(rawToken, fixture.match.id(), request).status())
                .isEqualTo("WAITING_FOR_SECOND_WATCHER");
        assertThat(fixture.service.observe(rawToken, fixture.match.id(), request).status())
                .isEqualTo("WAITING_FOR_SECOND_WATCHER");
        verify(fixture.observations, never()).save(any());
        verify(fixture.matchService, never()).recordVerifiedDuelResult(any(), any(), any());
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
        final UUID host = UUID.randomUUID();
        final UUID guest = UUID.randomUUID();
        final DuelWatcherTokenRepository tokens = mock(DuelWatcherTokenRepository.class);
        final DuelWatcherObservationRepository observations = mock(DuelWatcherObservationRepository.class);
        final DuelChallengeRepository challenges = mock(DuelChallengeRepository.class);
        final GameMatchRepository matches = mock(GameMatchRepository.class);
        final MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        final LobbyCredentialService credentials = mock(LobbyCredentialService.class);
        final MatchApplicationService matchService = mock(MatchApplicationService.class);
        final MatchRealtimeHub realtime = mock(MatchRealtimeHub.class);
        final GameMatch match;
        final DuelChallenge challenge;
        final DuelWatcherService service;

        Fixture() {
            match = GameMatch.readyCheck(
                    UUID.randomUUID(), "EUW", "ONE_V_ONE", NOW, NOW.plusSeconds(30));
            match.confirm("SWD-TEST", "encrypted-password");
            challenge = DuelChallenge.pending(
                    host, guest, "Claude Code#JAVA", "Codex#GPT", "EUW", NOW, NOW.plusSeconds(90));
            challenge.accept(match.id(), NOW);
            when(matches.findById(match.id())).thenReturn(Optional.of(match));
            service = new DuelWatcherService(
                    tokens, observations, challenges, matches, players, credentials,
                    matchService, realtime, Duration.ofMinutes(2));
        }
    }
}
