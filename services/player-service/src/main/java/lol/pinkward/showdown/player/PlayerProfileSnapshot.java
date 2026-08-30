package lol.pinkward.showdown.player;

import java.time.Instant;
import java.util.UUID;

record PlayerProfileSnapshot(
        UUID playerId,
        String displayName,
        String region,
        String primaryRole,
        String secondaryRole,
        boolean onboardingComplete,
        String riotGameName,
        String riotTagLine,
        String riotId,
        Integer riotProfileIconId,
        Long riotSummonerLevel,
        Instant riotLinkedAt,
        boolean online,
        Instant lastSeenAt,
        Instant updatedAt) {

    static PlayerProfileSnapshot from(PlayerProfile profile, Instant now) {
        return new PlayerProfileSnapshot(
                profile.playerId(),
                profile.displayName(),
                profile.region(),
                profile.primaryRole(),
                profile.secondaryRole(),
                profile.onboardingCompleted(),
                profile.riotGameName(),
                profile.riotTagLine(),
                profile.riotGameName() == null ? null : profile.riotGameName() + "#" + profile.riotTagLine(),
                profile.riotProfileIconId(),
                profile.riotSummonerLevel(),
                profile.riotLinkedAt(),
                profile.online(now),
                profile.lastSeenAt(),
                profile.updatedAt());
    }
}
