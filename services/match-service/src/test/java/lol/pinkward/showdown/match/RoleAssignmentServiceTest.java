package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import lol.pinkward.showdown.contracts.PlayerRolePreference;
import org.junit.jupiter.api.Test;

class RoleAssignmentServiceTest {

    @Test
    void assignsEveryLaneOnceAndMaximizesPrimaryPreferences() {
        var players = IntStream.range(0, 5).mapToObj(index -> UUID.randomUUID()).toList();
        var roles = List.of("TOP", "JUNGLE", "MID", "BOT", "SUPPORT");
        var preferences = IntStream.range(0, 5)
                .mapToObj(index -> new PlayerRolePreference(
                        players.get(index), roles.get(index), roles.get((index + 1) % 5)))
                .collect(java.util.stream.Collectors.toMap(PlayerRolePreference::playerId, value -> value));

        var assigned = new RoleAssignmentService().assign(players, preferences);

        assertThat(assigned.values()).containsExactlyInAnyOrder(LaneRole.values());
        players.forEach(player -> assertThat(assigned.get(player).name())
                .isEqualTo(preferences.get(player).primaryRole()));
    }

    @Test
    void neverSacrificesTheOldestPlayersPrimaryRoleForMoreSecondaryRoles() {
        var players = IntStream.range(0, 5).mapToObj(index -> UUID.randomUUID()).toList();
        var preferences = List.of(
                        new PlayerRolePreference(players.get(0), "JUNGLE", "MID"),
                        new PlayerRolePreference(players.get(1), "JUNGLE", "TOP"),
                        new PlayerRolePreference(players.get(2), "TOP", "JUNGLE"),
                        new PlayerRolePreference(players.get(3), "BOT", "SUPPORT"),
                        new PlayerRolePreference(players.get(4), "SUPPORT", "BOT"))
                .stream()
                .collect(java.util.stream.Collectors.toMap(PlayerRolePreference::playerId, value -> value));

        var assigned = new RoleAssignmentService().assign(players, preferences);

        assertThat(assigned.get(players.getFirst())).isEqualTo(LaneRole.JUNGLE);
        assertThat(assigned.values()).containsExactlyInAnyOrder(LaneRole.values());
    }
}
