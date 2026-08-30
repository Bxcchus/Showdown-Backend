package lol.pinkward.showdown.match;

import java.util.UUID;

record MatchParticipantDetail(
        UUID playerId,
        String team,
        String role,
        boolean bot,
        boolean self) {}
