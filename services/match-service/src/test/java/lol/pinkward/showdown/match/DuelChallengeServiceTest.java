package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class DuelChallengeServiceTest {

    @Test
    void usesOnlyAuthoritativePlayerIdentitiesWhenCreatingADuel() {
        Fixture fixture = new Fixture();
        UUID challenger = UUID.randomUUID();
        UUID opponent = UUID.randomUUID();
        when(fixture.identities.resolve(challenger))
                .thenReturn(new DuelIdentityClient.DuelIdentity(challenger, "host-puuid-00000001", "Verified Host#EUW", "EUW"));
        when(fixture.identities.resolve(opponent))
                .thenReturn(new DuelIdentityClient.DuelIdentity(opponent, "guest-puuid-0000001", "Verified Guest#EUW", "EUW"));
        when(fixture.matches.findActiveForPlayer(any())).thenReturn(Optional.empty());
        when(fixture.challenges.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        fixture.service.create(challenger, new CreateDuelChallengeRequest(opponent));

        ArgumentCaptor<DuelChallenge> saved = ArgumentCaptor.forClass(DuelChallenge.class);
        verify(fixture.challenges).save(saved.capture());
        assertThat(saved.getValue().challengerRiotId()).isEqualTo("Verified Host#EUW");
        assertThat(saved.getValue().opponentRiotId()).isEqualTo("Verified Guest#EUW");
        assertThat(saved.getValue().region()).isEqualTo("EUW");
    }

    @Test
    void rejectsPlayersFromDifferentRegionsBeforePersisting() {
        Fixture fixture = new Fixture();
        UUID challenger = UUID.randomUUID();
        UUID opponent = UUID.randomUUID();
        when(fixture.identities.resolve(challenger))
                .thenReturn(new DuelIdentityClient.DuelIdentity(challenger, "host-puuid-00000001", "Host Name#EUW", "EUW"));
        when(fixture.identities.resolve(opponent))
                .thenReturn(new DuelIdentityClient.DuelIdentity(opponent, "guest-puuid-0000001", "Guest Name#NA1", "NA"));

        assertThatThrownBy(() -> fixture.service.create(challenger, new CreateDuelChallengeRequest(opponent)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("same region");
        verify(fixture.challenges, never()).save(any());
    }

    private static final class Fixture {
        final DuelChallengeRepository challenges = mock(DuelChallengeRepository.class);
        final GameMatchRepository matches = mock(GameMatchRepository.class);
        final MatchPlayerRepository players = mock(MatchPlayerRepository.class);
        final DuelIdentityClient identities = mock(DuelIdentityClient.class);
        final DuelChallengeService service = new DuelChallengeService(
                challenges,
                matches,
                players,
                mock(LobbyCredentialService.class),
                mock(MatchRealtimeHub.class),
                identities,
                Duration.ofMinutes(10));
    }
}
