package lol.pinkward.showdown.player;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class RiotLinkService {

    private final RiotLinkChallengeRepository challenges;
    private final PlayerProfileService profiles;
    private final Duration challengeTtl;
    private final Clock clock;

    @Autowired
    RiotLinkService(
            RiotLinkChallengeRepository challenges,
            PlayerProfileService profiles,
            @Value("${pinkward.riot-link.challenge-ttl:90s}") Duration challengeTtl) {
        this(challenges, profiles, challengeTtl, Clock.systemUTC());
    }

    RiotLinkService(
            RiotLinkChallengeRepository challenges,
            PlayerProfileService profiles,
            Duration challengeTtl,
            Clock clock) {
        this.challenges = challenges;
        this.profiles = profiles;
        this.challengeTtl = challengeTtl;
        this.clock = clock;
    }

    @Transactional
    RiotLinkChallengeResponse issue(UUID playerId, String identityUsername) {
        Instant now = clock.instant();
        profiles.me(playerId, identityUsername);
        challenges.deleteByExpiresAtBefore(now.minus(Duration.ofMinutes(5)));
        challenges.deleteByPlayerIdAndConsumedAtIsNull(playerId);
        RiotLinkChallenge challenge = challenges.save(
                RiotLinkChallenge.issue(playerId, now, now.plus(challengeTtl)));
        return new RiotLinkChallengeResponse(challenge.challengeId(), challenge.expiresAt());
    }

    @Transactional
    PlayerProfileSnapshot complete(UUID challengeId, UUID authenticatedPlayerId,
            VerifiedRiotIdentityRequest identity) {
        Instant now = clock.instant();
        RiotLinkChallenge challenge = challenges.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Riot link challenge not found"));
        if (!challenge.active(now)) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "Riot link challenge is expired or already consumed");
        }
        if (!challenge.playerId().equals(authenticatedPlayerId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Riot link challenge belongs to another player");
        }
        PlayerProfileSnapshot profile = profiles.linkVerifiedRiotId(
                challenge.playerId(),
                identity.puuid(),
                identity.gameName(),
                identity.tagLine(),
                identity.profileIconId(),
                identity.summonerLevel());
        challenge.consume(now);
        return profile;
    }
}
