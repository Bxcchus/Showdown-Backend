package lol.pinkward.showdown.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "player_profiles")
class PlayerProfile {

    @Id
    @Column(name = "player_id")
    private UUID playerId;

    @Column(name = "display_name", nullable = false, length = 24)
    private String displayName;

    @Column(nullable = false, length = 16)
    private String region;

    @Column(name = "primary_role", nullable = false, length = 16)
    private String primaryRole;

    @Column(name = "secondary_role", nullable = false, length = 16)
    private String secondaryRole;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "presence_expires_at")
    private Instant presenceExpiresAt;

    @Column(name = "riot_game_name", length = 16)
    private String riotGameName;

    @Column(name = "riot_tag_line", length = 5)
    private String riotTagLine;

    @Column(name = "riot_puuid", length = 128)
    private String riotPuuid;

    @Column(name = "riot_linked_at")
    private Instant riotLinkedAt;

    @Column(name = "riot_profile_icon_id")
    private Integer riotProfileIconId;

    @Column(name = "riot_summoner_level")
    private Long riotSummonerLevel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PlayerProfile() {}

    static PlayerProfile create(UUID playerId, String displayName, Instant now, Duration presenceTtl) {
        PlayerProfile profile = new PlayerProfile();
        profile.playerId = playerId;
        profile.displayName = displayName;
        profile.region = "EUW";
        profile.primaryRole = "MID";
        profile.secondaryRole = "JUNGLE";
        profile.onboardingCompleted = false;
        profile.createdAt = now;
        profile.updatedAt = now;
        profile.heartbeat(now, presenceTtl);
        return profile;
    }

    void update(String displayName, String region, String primaryRole, String secondaryRole, Instant now) {
        this.displayName = displayName;
        this.region = region;
        this.primaryRole = primaryRole;
        this.secondaryRole = secondaryRole;
        this.onboardingCompleted = true;
        this.updatedAt = now;
    }

    void heartbeat(Instant now, Duration presenceTtl) {
        lastSeenAt = now;
        presenceExpiresAt = now.plus(presenceTtl);
    }

    void offline(Instant now) {
        lastSeenAt = now;
        presenceExpiresAt = now;
    }

    void linkRiotId(String gameName, String tagLine, Integer profileIconId, Long summonerLevel, Instant now) {
        riotGameName = gameName;
        riotTagLine = tagLine;
        riotProfileIconId = profileIconId;
        riotSummonerLevel = summonerLevel;
        riotLinkedAt = now;
        updatedAt = now;
    }

    void linkVerifiedRiotId(String puuid, String gameName, String tagLine,
            Integer profileIconId, Long summonerLevel, Instant now) {
        riotPuuid = puuid;
        linkRiotId(gameName, tagLine, profileIconId, summonerLevel, now);
    }

    boolean online(Instant now) {
        return presenceExpiresAt != null && presenceExpiresAt.isAfter(now);
    }

    UUID playerId() { return playerId; }
    String displayName() { return displayName; }
    String region() { return region; }
    String primaryRole() { return primaryRole; }
    String secondaryRole() { return secondaryRole; }
    boolean onboardingCompleted() { return onboardingCompleted; }
    Instant lastSeenAt() { return lastSeenAt; }
    Instant updatedAt() { return updatedAt; }
    String riotGameName() { return riotGameName; }
    String riotTagLine() { return riotTagLine; }
    String riotPuuid() { return riotPuuid; }
    Instant riotLinkedAt() { return riotLinkedAt; }
    Integer riotProfileIconId() { return riotProfileIconId; }
    Long riotSummonerLevel() { return riotSummonerLevel; }
}
