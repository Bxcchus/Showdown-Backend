package lol.pinkward.showdown.contracts;

import java.util.List;
import java.util.UUID;

public record MatchFoundPayload(
        UUID reservationId,
        String region,
        String mode,
        List<UUID> playerIds,
        List<UUID> botPlayerIds,
        List<PlayerRolePreference> rolePreferences) {

    public MatchFoundPayload {
        playerIds = List.copyOf(playerIds);
        botPlayerIds = List.copyOf(botPlayerIds);
        rolePreferences = List.copyOf(rolePreferences);
        int expectedPlayers = mode == null ? -1 : switch (mode) {
            case "FIVE_V_FIVE" -> 10;
            case "ONE_V_ONE" -> 2;
            default -> -1;
        };
        if (reservationId == null || region == null || region.isBlank()
                || expectedPlayers < 0 || playerIds.size() != expectedPlayers
                || playerIds.stream().distinct().count() != expectedPlayers
                || botPlayerIds.stream().distinct().count() != botPlayerIds.size()
                || !playerIds.containsAll(botPlayerIds)
                || ("ONE_V_ONE".equals(mode) && botPlayerIds.size() > 1)
                || rolePreferences.size() != expectedPlayers
                || rolePreferences.stream().map(PlayerRolePreference::playerId).distinct().count() != expectedPlayers
                || !playerIds.containsAll(rolePreferences.stream().map(PlayerRolePreference::playerId).toList())) {
            throw new IllegalArgumentException("A match-found payload has an invalid roster for mode " + mode);
        }
    }
}
