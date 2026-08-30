package lol.pinkward.showdown.match;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

record CreateDuelChallengeRequest(@NotNull UUID opponentId) {}
