package lol.pinkward.showdown.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RiotLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

    @Test
    void issuesAShortLivedChallengeBoundToTheAuthenticatedPlayer() {
        RiotLinkChallengeRepository challenges = mock(RiotLinkChallengeRepository.class);
        PlayerProfileService profiles = mock(PlayerProfileService.class);
        when(challenges.save(org.mockito.ArgumentMatchers.any(RiotLinkChallenge.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        RiotLinkService service = service(challenges, profiles);
        UUID playerId = UUID.randomUUID();

        RiotLinkChallengeResponse response = service.issue(playerId, "local-player");

        assertThat(response.challengeId()).isNotNull();
        assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(90));
        verify(profiles).me(playerId, "local-player");
        verify(challenges).deleteByPlayerIdAndConsumedAtIsNull(playerId);
    }

    @Test
    void consumesTheChallengeBeforeItCanBeReplayed() {
        RiotLinkChallengeRepository challenges = mock(RiotLinkChallengeRepository.class);
        PlayerProfileService profiles = mock(PlayerProfileService.class);
        UUID playerId = UUID.randomUUID();
        RiotLinkChallenge challenge = RiotLinkChallenge.issue(playerId, NOW, NOW.plusSeconds(90));
        when(challenges.findByIdForUpdate(challenge.challengeId())).thenReturn(Optional.of(challenge));
        PlayerProfileSnapshot expected = mock(PlayerProfileSnapshot.class);
        when(profiles.linkVerifiedRiotId(
                playerId, "verified-puuid-1234567890", "Claude Code", "JAVA", 29, 87L))
                .thenReturn(expected);
        RiotLinkService service = service(challenges, profiles);
        VerifiedRiotIdentityRequest identity = new VerifiedRiotIdentityRequest(
                "verified-puuid-1234567890", "Claude Code", "JAVA", 29, 87L);

        assertThat(service.complete(challenge.challengeId(), playerId, identity)).isSameAs(expected);
        assertThatThrownBy(() -> service.complete(challenge.challengeId(), playerId, identity))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already consumed");
    }

    @Test
    void refusesAnExpiredChallenge() {
        RiotLinkChallengeRepository challenges = mock(RiotLinkChallengeRepository.class);
        PlayerProfileService profiles = mock(PlayerProfileService.class);
        RiotLinkChallenge challenge = RiotLinkChallenge.issue(
                UUID.randomUUID(), NOW.minusSeconds(120), NOW.minusSeconds(30));
        when(challenges.findByIdForUpdate(challenge.challengeId())).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service(challenges, profiles).complete(
                challenge.challengeId(),
                challenge.playerId(),
                new VerifiedRiotIdentityRequest(
                        "verified-puuid-1234567890", "Claude Code", "JAVA", 29, 87L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void refusesAChallengeOwnedByAnotherAuthenticatedPlayer() {
        RiotLinkChallengeRepository challenges = mock(RiotLinkChallengeRepository.class);
        PlayerProfileService profiles = mock(PlayerProfileService.class);
        UUID owner = UUID.randomUUID();
        RiotLinkChallenge challenge = RiotLinkChallenge.issue(owner, NOW, NOW.plusSeconds(90));
        when(challenges.findByIdForUpdate(challenge.challengeId())).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service(challenges, profiles).complete(
                challenge.challengeId(),
                UUID.randomUUID(),
                new VerifiedRiotIdentityRequest(
                        "verified-puuid-1234567890", "Claude Code", "JAVA", 29, 87L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("another player");
    }

    private RiotLinkService service(
            RiotLinkChallengeRepository challenges, PlayerProfileService profiles) {
        return new RiotLinkService(
                challenges, profiles, Duration.ofSeconds(90), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
