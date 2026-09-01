package lol.pinkward.showdown.match;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "match_players")
class MatchPlayer {

    @Id
    private UUID id;

    @Column(name = "match_id", nullable = false)
    private UUID matchId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private TeamSide team;

    @Enumerated(EnumType.STRING)
    @Column(name = "ready_state", nullable = false, length = 16)
    private ReadyState readyState;

    @Column(name = "is_bot", nullable = false)
    private boolean bot;

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_role", nullable = false, length = 16)
    private LaneRole assignedRole;

    @Column(name = "champion_name", length = 64)
    private String championName;

    @Column(name = "kills")
    private Integer kills;

    @Column(name = "deaths")
    private Integer deaths;

    @Column(name = "assists")
    private Integer assists;

    @Column(name = "item_ids", length = 96)
    private String itemIds;

    protected MatchPlayer() {}

    static MatchPlayer pending(UUID matchId, UUID playerId, TeamSide team) {
        return readyCheck(matchId, playerId, team, false, LaneRole.MID);
    }

    static MatchPlayer readyCheck(
            UUID matchId, UUID playerId, TeamSide team, boolean bot, LaneRole assignedRole) {
        MatchPlayer player = new MatchPlayer();
        player.id = UUID.randomUUID();
        player.matchId = matchId;
        player.playerId = playerId;
        player.team = team;
        player.readyState = bot ? ReadyState.ACCEPTED : ReadyState.PENDING;
        player.bot = bot;
        player.assignedRole = assignedRole;
        return player;
    }

    void answer(boolean accepted) {
        if (readyState == ReadyState.PENDING) {
            readyState = accepted ? ReadyState.ACCEPTED : ReadyState.DECLINED;
        }
    }

    void recordChampion(String championName) {
        if (championName == null || championName.isBlank()) return;
        String normalized = championName.trim();
        if (this.championName == null || this.championName.equalsIgnoreCase(normalized)) {
            this.championName = normalized;
        }
    }

    void recordPerformance(Integer kills, Integer deaths, Integer assists, List<Integer> itemIds) {
        if (kills == null || deaths == null || assists == null) return;
        this.kills = kills;
        this.deaths = deaths;
        this.assists = assists;
        this.itemIds = itemIds == null ? "" : itemIds.stream()
                .filter(java.util.Objects::nonNull)
                .filter(itemId -> itemId > 0)
                .limit(7)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    UUID matchId() { return matchId; }
    UUID playerId() { return playerId; }
    TeamSide team() { return team; }
    ReadyState readyState() { return readyState; }
    boolean bot() { return bot; }
    LaneRole assignedRole() { return assignedRole; }
    String championName() { return championName; }
    Integer kills() { return kills; }
    Integer deaths() { return deaths; }
    Integer assists() { return assists; }
    List<Integer> itemIds() {
        if (itemIds == null || itemIds.isBlank()) return List.of();
        return Arrays.stream(itemIds.split(",")).map(Integer::valueOf).toList();
    }
}
