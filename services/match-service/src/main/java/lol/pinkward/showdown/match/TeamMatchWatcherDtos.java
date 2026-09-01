package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

record TeamWatcherPerformance(
        @NotBlank String puuid,
        @NotNull @Min(0) @Max(999) Integer kills,
        @NotNull @Min(0) @Max(999) Integer deaths,
        @NotNull @Min(0) @Max(999) Integer assists,
        @Size(max = 7) List<@Min(1) @Max(999999) Integer> itemIds) {}

record TeamWatcherAssignment(
        UUID matchId, String role, UUID ownPlayerId, String ownPuuid, String ownRiotId,
        String ownTeam, String region, String lobbyName, String lobbyPassword,
        List<TeamWatcherPlayer> players, String state, Instant expiresAt) {}

record TeamWatcherResultRequest(
        @NotNull TeamSide winningTeam,
        @NotBlank String gameId,
        @NotEmpty List<@NotBlank String> verifiedPuuids,
        List<@Valid TeamWatcherChampion> champions,
        List<@Valid TeamWatcherPerformance> performances,
        @NotNull Instant observedAt) {}

record TeamWatcherResultResponse(String status, TeamSide winningTeam, String gameId) {}
