package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

record BotMatchResultRequest(
        @NotNull Boolean humanWon,
        @NotNull DuelObjective objective,
        @Size(max = 64) String championName,
        @Min(0) @Max(999) Integer kills,
        @Min(0) @Max(999) Integer deaths,
        @Min(0) @Max(999) Integer assists,
        @Size(max = 7) List<@Min(1) @Max(999999) Integer> itemIds,
        @NotNull Instant observedAt) {}
