package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

record BotMatchResultRequest(
        @NotNull Boolean humanWon,
        @NotNull DuelObjective objective,
        @NotNull Instant observedAt) {}
