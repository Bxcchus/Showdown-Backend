package lol.pinkward.showdown.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PlayerProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void createsTheDefaultProfileAndMarksItOnline() {
        PlayerProfileRepository repository = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        when(repository.findById(playerId)).thenReturn(Optional.empty());
        when(repository.save(org.mockito.ArgumentMatchers.any(PlayerProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var service = service(repository);

        PlayerProfileSnapshot result = service.me(playerId, "local-player");

        assertThat(result.displayName()).isEqualTo("local-player");
        assertThat(result.region()).isEqualTo("EUW");
        assertThat(result.primaryRole()).isEqualTo("MID");
        assertThat(result.secondaryRole()).isEqualTo("JUNGLE");
        assertThat(result.online()).isTrue();
    }

    @Test
    void updatesRegionRolesAndDisplayName() {
        PlayerProfileRepository repository = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        PlayerProfile profile = PlayerProfile.create(playerId, "local-player", NOW, Duration.ofSeconds(45));
        when(repository.findById(playerId)).thenReturn(Optional.of(profile));
        var service = service(repository);

        PlayerProfileSnapshot result = service.update(
                playerId, "local-player", "Jungle Main", "NA", "JUNGLE", "MID");

        assertThat(result.displayName()).isEqualTo("Jungle Main");
        assertThat(result.region()).isEqualTo("NA");
        assertThat(result.primaryRole()).isEqualTo("JUNGLE");
        assertThat(result.secondaryRole()).isEqualTo("MID");
    }

    @Test
    void rejectsEqualFavoriteRoles() {
        PlayerProfileRepository repository = mock(PlayerProfileRepository.class);
        var service = service(repository);

        assertThatThrownBy(() -> service.update(
                UUID.randomUUID(), "local-player", "local-player", "EUW", "JUNGLE", "JUNGLE"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void explicitlyMarksThePlayerOffline() {
        PlayerProfileRepository repository = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        PlayerProfile profile = PlayerProfile.create(playerId, "local-player", NOW, Duration.ofSeconds(45));
        when(repository.findById(playerId)).thenReturn(Optional.of(profile));
        var service = service(repository);

        PlayerProfileSnapshot result = service.offline(playerId, "local-player");

        assertThat(result.online()).isFalse();
    }

    @Test
    void readingAProfileDoesNotMutatePresence() {
        PlayerProfileRepository repository = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        PlayerProfile profile = PlayerProfile.create(playerId, "local-player", NOW, Duration.ofSeconds(45));
        profile.offline(NOW);
        when(repository.findById(playerId)).thenReturn(Optional.of(profile));

        PlayerProfileSnapshot result = service(repository).me(playerId, "local-player");

        assertThat(result.online()).isFalse();
    }

    @Test
    void linksTheRiotIdDetectedByTheWatcher() {
        PlayerProfileRepository repository = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        PlayerProfile profile = PlayerProfile.create(playerId, "local-player", NOW, Duration.ofSeconds(45));
        when(repository.findById(playerId)).thenReturn(Optional.of(profile));

        PlayerProfileSnapshot result = service(repository)
                .linkRiotId(playerId, "local-player", " Claude  Code ", "java");

        assertThat(result.riotId()).isEqualTo("Claude Code#JAVA");
        assertThat(result.riotLinkedAt()).isEqualTo(NOW);
    }

    @Test
    void storesTheStablePuuidForAVerifiedWatcherLink() {
        PlayerProfileRepository repository = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        PlayerProfile profile = PlayerProfile.create(playerId, "local-player", NOW, Duration.ofSeconds(45));
        when(repository.findById(playerId)).thenReturn(Optional.of(profile));
        when(repository.findByRiotPuuid("verified-puuid-1234567890")).thenReturn(Optional.empty());
        when(repository.findByRiotGameNameIgnoreCaseAndRiotTagLineIgnoreCase("Claude Code", "JAVA"))
                .thenReturn(Optional.empty());

        PlayerProfileSnapshot result = service(repository).linkVerifiedRiotId(
                playerId, "verified-puuid-1234567890", "Claude Code", "JAVA", 29, 87L);

        assertThat(result.riotId()).isEqualTo("Claude Code#JAVA");
        assertThat(profile.riotPuuid()).isEqualTo("verified-puuid-1234567890");
        assertThat(result.riotProfileIconId()).isEqualTo(29);
        assertThat(result.riotSummonerLevel()).isEqualTo(87L);
    }

    @Test
    void refusesAPuuidAlreadyLinkedToAnotherPlayer() {
        PlayerProfileRepository repository = mock(PlayerProfileRepository.class);
        UUID playerId = UUID.randomUUID();
        PlayerProfile profile = PlayerProfile.create(playerId, "local-player", NOW, Duration.ofSeconds(45));
        PlayerProfile owner = PlayerProfile.create(UUID.randomUUID(), "other-player", NOW, Duration.ofSeconds(45));
        when(repository.findById(playerId)).thenReturn(Optional.of(profile));
        when(repository.findByRiotPuuid("verified-puuid-1234567890")).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service(repository).linkVerifiedRiotId(
                playerId, "verified-puuid-1234567890", "Claude Code", "JAVA", 29, 87L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already linked");
    }

    private static PlayerProfileService service(PlayerProfileRepository repository) {
        return new PlayerProfileService(
                repository,
                Duration.ofSeconds(45),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
