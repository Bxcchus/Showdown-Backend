package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record TeamWatcherPlayer(
        UUID playerId, String puuid, String riotId, String team, String assignedRole, boolean bot) {}

record TeamWatcherAssignment(
        UUID matchId, String role, UUID ownPlayerId, String ownPuuid, String ownRiotId,
        String ownTeam, String region, String lobbyName, String lobbyPassword,
        List<TeamWatcherPlayer> players, String state, Instant expiresAt) {}

record TeamWatcherResultRequest(
        @NotNull TeamSide winningTeam,
        @NotBlank String gameId,
        @NotEmpty List<@NotBlank String> verifiedPuuids,
        @NotNull Instant observedAt) {}

record TeamWatcherResultResponse(String status, TeamSide winningTeam, String gameId) {}
