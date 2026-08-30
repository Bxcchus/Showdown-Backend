package lol.pinkward.showdown.contracts;

import java.util.Set;
import java.util.UUID;

public record PlayerRolePreference(UUID playerId, String primaryRole, String secondaryRole) {

    private static final Set<String> ROLES = Set.of("TOP", "JUNGLE", "MID", "BOT", "SUPPORT");

    public PlayerRolePreference {
        if (playerId == null || !ROLES.contains(primaryRole) || !ROLES.contains(secondaryRole)
                || primaryRole.equals(secondaryRole)) {
            throw new IllegalArgumentException("Role preferences require two distinct supported roles");
        }
    }
}
