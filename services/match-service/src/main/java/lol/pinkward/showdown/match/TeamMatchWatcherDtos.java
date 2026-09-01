package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record TeamWatcherPlayer(
        UUID playerId, String puuid, String riotId, String team, String assignedRole, boolean bot) {}

record TeamWatcherChampion(
        @NotBlank String puuid,
        @NotBlank @Size(max = 64) String championName) {}

record TeamWatcherAssignment(
        UUID matchId, String role, UUID ownPlayerId, String ownPuuid, String ownRiotId,
        String ownTeam, String region, String lobbyName, String lobbyPassword,
        List<TeamWatcherPlayer> players, String state, Instant expiresAt) {}

record TeamWatcherResultRequest(
        @NotNull TeamSide winningTeam,
        @NotBlank String gameId,
        @NotEmpty List<@NotBlank String> verifiedPuuids,
        List<@Valid TeamWatcherChampion> champions,
        @NotNull Instant observedAt) {}

record TeamWatcherResultResponse(String status, TeamSide winningTeam, String gameId) {}
