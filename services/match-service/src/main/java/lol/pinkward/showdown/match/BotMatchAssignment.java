package lol.pinkward.showdown.match;

import java.util.UUID;

record BotMatchAssignment(UUID matchId, String lobbyName, String lobbyPassword) {}
