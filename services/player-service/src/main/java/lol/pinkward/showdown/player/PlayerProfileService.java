package lol.pinkward.showdown.player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PlayerProfileService {

    private static final Set<String> REGIONS = Set.of("EUW", "EUNE", "NA");
    private static final Set<String> ROLES = Set.of("TOP", "JUNGLE", "MID", "BOT", "SUPPORT");

    private final PlayerProfileRepository profiles;
    private final Duration presenceTtl;
    private final Clock clock;

    @Autowired
    PlayerProfileService(
            PlayerProfileRepository profiles,
            @Value("${pinkward.presence.ttl:45s}") Duration presenceTtl) {
        this(profiles, presenceTtl, Clock.systemUTC());
    }

    PlayerProfileService(PlayerProfileRepository profiles, Duration presenceTtl, Clock clock) {
        this.profiles = profiles;
        this.presenceTtl = presenceTtl;
        this.clock = clock;
    }

    @Transactional
    PlayerProfileSnapshot me(UUID playerId, String identityUsername) {
        Instant now = clock.instant();
        PlayerProfile profile = getOrCreate(playerId, identityUsername, now);
        return PlayerProfileSnapshot.from(profile, now);
    }

    @Transactional
    PlayerProfileSnapshot update(
            UUID playerId,
            String identityUsername,
            String displayName,
            String region,
            String primaryRole,
            String secondaryRole) {
        Instant now = clock.instant();
        String normalizedName = normalizeDisplayName(displayName);
        String normalizedRegion = normalize(region, REGIONS, "Unsupported region");
        String normalizedPrimary = normalize(primaryRole, ROLES, "Unsupported primary role");
        String normalizedSecondary = normalize(secondaryRole, ROLES, "Unsupported secondary role");
        if (normalizedPrimary.equals(normalizedSecondary)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Primary and secondary roles must differ");
        }
        if (profiles.existsByDisplayNameIgnoreCaseAndPlayerIdNot(normalizedName, playerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Display name is already used");
        }
        PlayerProfile profile = getOrCreate(playerId, identityUsername, now);
        profile.update(normalizedName, normalizedRegion, normalizedPrimary, normalizedSecondary, now);
        profile.heartbeat(now, presenceTtl);
        return PlayerProfileSnapshot.from(profile, now);
    }

    @Transactional
    PlayerProfileSnapshot heartbeat(UUID playerId, String identityUsername) {
        Instant now = clock.instant();
        PlayerProfile profile = getOrCreate(playerId, identityUsername, now);
        profile.heartbeat(now, presenceTtl);
        return PlayerProfileSnapshot.from(profile, now);
    }

    @Transactional
    PlayerProfileSnapshot offline(UUID playerId, String identityUsername) {
        Instant now = clock.instant();
        PlayerProfile profile = getOrCreate(playerId, identityUsername, now);
        profile.offline(now);
        return PlayerProfileSnapshot.from(profile, now);
    }

    @Transactional
    PlayerProfileSnapshot linkRiotId(
            UUID playerId, String identityUsername, String gameName, String tagLine) {
        return linkRiotId(playerId, identityUsername, gameName, tagLine, null, null);
    }

    @Transactional
    PlayerProfileSnapshot linkRiotId(
            UUID playerId, String identityUsername, String gameName, String tagLine,
            Integer profileIconId, Long summonerLevel) {
        Instant now = clock.instant();
        String normalizedGameName = normalizeRiotGameName(gameName);
        String normalizedTagLine = normalizeRiotTagLine(tagLine);
        PlayerProfile profile = getOrCreate(playerId, identityUsername, now);
        Integer normalizedIcon = profileIconId != null && profileIconId > 0 ? profileIconId : null;
        Long normalizedLevel = summonerLevel != null && summonerLevel > 0 ? summonerLevel : null;
        profile.linkRiotId(normalizedGameName, normalizedTagLine, normalizedIcon, normalizedLevel, now);
        profile.heartbeat(now, presenceTtl);
        return PlayerProfileSnapshot.from(profile, now);
    }

    @Transactional
    PlayerProfileSnapshot linkVerifiedRiotId(
            UUID playerId, String puuid, String gameName, String tagLine,
            Integer profileIconId, Long summonerLevel) {
        Instant now = clock.instant();
        String normalizedPuuid = normalizePuuid(puuid);
        String normalizedGameName = normalizeRiotGameName(gameName);
        String normalizedTagLine = normalizeRiotTagLine(tagLine);
        PlayerProfile profile = profiles.findById(playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player profile not found"));
        profiles.findByRiotPuuid(normalizedPuuid)
                .filter(owner -> !owner.playerId().equals(playerId))
                .ifPresent(owner -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Riot account is already linked");
                });
        profiles.findByRiotGameNameIgnoreCaseAndRiotTagLineIgnoreCase(normalizedGameName, normalizedTagLine)
                .filter(owner -> !owner.playerId().equals(playerId))
                .ifPresent(owner -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Riot account is already linked");
                });
        Integer normalizedIcon = profileIconId != null && profileIconId > 0 ? profileIconId : null;
        Long normalizedLevel = summonerLevel != null && summonerLevel > 0 ? summonerLevel : null;
        profile.linkVerifiedRiotId(
                normalizedPuuid, normalizedGameName, normalizedTagLine,
                normalizedIcon, normalizedLevel, now);
        profile.heartbeat(now, presenceTtl);
        return PlayerProfileSnapshot.from(profile, now);
    }

    private PlayerProfile getOrCreate(UUID playerId, String identityUsername, Instant now) {
        return profiles.findById(playerId).orElseGet(() -> profiles.save(PlayerProfile.create(
                playerId,
                normalizeDisplayName(identityUsername),
                now,
                presenceTtl)));
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Display name is required");
        }
        String normalized = displayName.trim().replaceAll("\\s+", " ");
        if (normalized.length() < 3 || normalized.length() > 24
                || !normalized.matches("[\\p{L}\\p{N}_. -]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Display name format is invalid");
        }
        return normalized;
    }

    private static String normalizeRiotGameName(String gameName) {
        if (gameName == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Riot game name is required");
        String normalized = gameName.trim().replaceAll("\\s+", " ");
        if (normalized.length() < 3 || normalized.length() > 16 || normalized.contains("#")
                || !normalized.matches("[\\p{L}\\p{N}_. -]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Riot game name format is invalid");
        }
        return normalized;
    }

    private static String normalizeRiotTagLine(String tagLine) {
        if (tagLine == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Riot tag line is required");
        String normalized = tagLine.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{3,5}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Riot tag line format is invalid");
        }
        return normalized;
    }

    private static String normalizePuuid(String puuid) {
        if (puuid == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Riot PUUID is required");
        String normalized = puuid.trim();
        if (normalized.length() < 16 || normalized.length() > 128
                || !normalized.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Riot PUUID format is invalid");
        }
        return normalized;
    }

    private static String normalize(String value, Set<String> supported, String error) {
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!supported.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        }
        return normalized;
    }
}
