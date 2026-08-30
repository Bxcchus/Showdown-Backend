package lol.pinkward.showdown.player;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

record RiotLinkChallengeResponse(UUID challengeId, Instant expiresAt) {}

record VerifiedRiotIdentityRequest(
        @NotBlank @Size(min = 16, max = 128) String puuid,
        @NotBlank @Size(min = 3, max = 16) String gameName,
        @NotBlank @Size(min = 3, max = 5) String tagLine,
        @Positive Integer profileIconId,
        @Positive Long summonerLevel) {}
