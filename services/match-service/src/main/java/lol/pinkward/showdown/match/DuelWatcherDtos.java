package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

record WatcherTokenResponse(String token, UUID matchId, String role, Instant expiresAt) {}
record WatcherAssignment(UUID matchId, String role, UUID ownPlayerId, String ownPuuid, String ownRiotId,
        UUID opponentPlayerId, String opponentRiotId, String region, String lobbyName,
        String lobbyPassword, String state, Instant expiresAt) {}
record WatcherStateResponse(UUID matchId, String state, Instant expiresAt) {}
record WatcherStateRequest(@NotBlank String state) {}
record WatcherObservationRequest(@NotNull DuelObjective objective, @NotBlank String winnerRiotId,
        @Size(max = 64) String championName,
        @NotNull Instant observedAt) {}
record WatcherObservationResponse(String status, DuelObjective objective, UUID winnerPlayerId) {}
