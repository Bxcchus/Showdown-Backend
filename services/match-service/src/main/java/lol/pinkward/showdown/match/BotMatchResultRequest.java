package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

record BotMatchResultRequest(
        @NotNull Boolean humanWon,
        @NotNull DuelObjective objective,
        @Size(max = 64) String championName,
        @NotNull Instant observedAt) {}
