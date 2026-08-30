package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotNull;

record MatchResultRequest(@NotNull TeamSide winningTeam) {}
