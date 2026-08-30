package lol.pinkward.showdown.match;

import java.util.UUID;

record MatchPlayerSnapshot(
        UUID playerId,
        String team,
        String readyState,
        boolean bot,
        String assignedRole) {

    static MatchPlayerSnapshot from(MatchPlayer player) {
        return new MatchPlayerSnapshot(
                player.playerId(),
                player.team().name(),
                player.readyState().name(),
                player.bot(),
                player.assignedRole().name());
    }
}
