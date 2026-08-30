package lol.pinkward.showdown.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PlayerInternalControllerTest {

    @Test
    void returnsOnlyTheVerifiedServerOwnedDuelIdentityWithoutCaching() {
        PlayerProfileRepository profiles = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        PlayerProfile profile = PlayerProfile.create(playerId, "Player", Instant.now(), Duration.ofSeconds(45));
        profile.linkVerifiedRiotId(
                "verified-riot-puuid-0001", "Verified Player", "EUW", 1, 30L, Instant.now());
        when(profiles.findById(playerId)).thenReturn(Optional.of(profile));

        var response = new PlayerInternalController(profiles).duelIdentity(playerId);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().puuid()).isEqualTo("verified-riot-puuid-0001");
        assertThat(response.getBody().riotId()).isEqualTo("Verified Player#EUW");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void refusesAnUnverifiedPlayerIdentity() {
        PlayerProfileRepository profiles = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        when(profiles.findById(playerId)).thenReturn(Optional.of(
                PlayerProfile.create(playerId, "Player", Instant.now(), Duration.ofSeconds(45))));

        assertThatThrownBy(() -> new PlayerInternalController(profiles).duelIdentity(playerId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no verified Riot identity");
    }
}
