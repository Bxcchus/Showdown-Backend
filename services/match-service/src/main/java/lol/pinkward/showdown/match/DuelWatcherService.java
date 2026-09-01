package lol.pinkward.showdown.match;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class DuelWatcherService {
    private static final Duration MAX_OBSERVATION_AGE = Duration.ofMinutes(2);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Set<String> STATES = Set.of("ISSUED", "LCU_CONNECTED", "LOBBY_CREATED",
            "INVITE_SENT", "JOINING", "JOINED", "BOTH_PRESENT", "CHAMP_SELECT_STARTED", "IN_GAME",
            "OBJECTIVE_RECORDED", "REVIEW_REQUIRED", "ERROR");
    private final DuelWatcherTokenRepository tokens;
    private final DuelWatcherObservationRepository observations;
    private final DuelChallengeRepository challenges;
    private final GameMatchRepository matches;
    private final MatchPlayerRepository players;
    private final LobbyCredentialService credentials;
    private final MatchApplicationService matchService;
    private final MatchRealtimeHub realtime;
    private final DuelIdentityClient identities;
    private final Duration tokenTtl;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    DuelWatcherService(DuelWatcherTokenRepository tokens, DuelWatcherObservationRepository observations,
            DuelChallengeRepository challenges, GameMatchRepository matches, MatchPlayerRepository players,
            LobbyCredentialService credentials, MatchApplicationService matchService, MatchRealtimeHub realtime,
            DuelIdentityClient identities,
            @Value("${pinkward.duel.watcher-token-ttl:2h}") Duration tokenTtl) {
        this.tokens = tokens; this.observations = observations; this.challenges = challenges;
        this.matches = matches; this.players = players; this.credentials = credentials;
        this.matchService = matchService; this.realtime = realtime; this.identities = identities;
        this.tokenTtl = tokenTtl;
    }

    @Transactional
    WatcherTokenResponse issue(UUID matchId, UUID playerId) {
        GameMatch match = duelMatch(matchId);
        players.findByMatchIdAndPlayerId(matchId, playerId)
                .orElseThrow(() -> forbidden("Player is not in this duel"));
        DuelChallenge challenge = challenges.findByMatchId(matchId)
                .orElseThrow(() -> notFound("Duel invitation not found"));
        String role = challenge.challengerId().equals(playerId) ? "HOST" : "GUEST";
        byte[] raw = new byte[32]; random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant(); Instant expiresAt = now.plus(tokenTtl);
        DuelWatcherToken value = tokens.findByMatchIdAndPlayerId(match.id(), playerId).orElse(null);
        if (value == null) value = DuelWatcherToken.issue(match.id(), playerId, hash(token), role, now, expiresAt);
        else value.rotate(hash(token), now, expiresAt);
        tokens.save(value);
        realtime.publishAfterCommit(List.of(playerId), "DUEL_WATCHER_TOKEN_ISSUED");
        return new WatcherTokenResponse(token, matchId, role, expiresAt);
    }

    @Transactional
    WatcherAssignment assignment(String rawToken, UUID requestedMatchId) {
        DuelWatcherToken token = authenticate(rawToken, requestedMatchId);
        DuelChallenge challenge = challenges.findByMatchId(token.matchId())
                .orElseThrow(() -> notFound("Duel invitation not found"));
        GameMatch match = duelMatch(token.matchId());
        boolean host = challenge.challengerId().equals(token.playerId());
        UUID opponentId = host ? challenge.opponentId() : challenge.challengerId();
        String ownRiotId = host ? challenge.challengerRiotId() : challenge.opponentRiotId();
        String opponentRiotId = host ? challenge.opponentRiotId() : challenge.challengerRiotId();
        DuelIdentityClient.DuelIdentity ownIdentity = identities.resolve(token.playerId());
        if (!ownIdentity.riotId().equalsIgnoreCase(ownRiotId)) {
            throw conflict("A Riot identity changed after this duel was accepted");
        }
        token.touch(token.state(), clock.instant());
        return new WatcherAssignment(match.id(), token.role(), token.playerId(), ownIdentity.puuid(), ownRiotId,
                opponentId, opponentRiotId, match.region(), match.lobbyName(),
                credentials.decrypt(match.lobbyPasswordEncrypted()), token.state(), token.expiresAt());
    }

    @Transactional
    WatcherStateResponse updateState(String rawToken, UUID matchId, String requestedState) {
        String state = requestedState == null ? "" : requestedState.trim().toUpperCase(Locale.ROOT);
        if (!STATES.contains(state)) throw bad("Unsupported watcher state");
        DuelWatcherToken token = authenticate(rawToken, matchId);
        token.touch(state, clock.instant());
        realtime.publishAfterCommit(List.of(token.playerId()), "DUEL_WATCHER_STATE");
        return new WatcherStateResponse(matchId, state, token.expiresAt());
    }

    @Transactional
    WatcherObservationResponse observe(String rawToken, UUID matchId, WatcherObservationRequest request) {
        DuelWatcherToken token = authenticate(rawToken, matchId);
        duelMatch(matchId);
        Instant now = clock.instant();
        if (request.observedAt().isBefore(now.minus(MAX_OBSERVATION_AGE))
                || request.observedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw bad("Watcher observation is outside the accepted time window");
        }
        MatchPlayer reporter = players.findByMatchIdAndPlayerId(matchId, token.playerId())
                .orElseThrow(() -> forbidden("Watcher player is not in this duel"));
        reporter.recordChampion(request.championName());
        DuelChallenge challenge = challenges.findByMatchId(matchId)
                .orElseThrow(() -> notFound("Duel invitation not found"));
        UUID winnerId = playerForRiotId(challenge, request.winnerRiotId());
        DuelWatcherObservation prior = observations
                .findByMatchIdAndReporterIdAndObjective(matchId, token.playerId(), request.objective()).orElse(null);
        if (prior != null && !prior.winnerPlayerId().equals(winnerId)) {
            throw conflict("This watcher already reported another winner for this objective");
        }
        if (prior == null) observations.save(DuelWatcherObservation.observed(matchId, token.playerId(),
                request.objective(), winnerId, request.observedAt(), now));
        token.touch("OBJECTIVE_RECORDED", now);
        long confirmations = observations.countByMatchIdAndObjectiveAndWinnerPlayerId(
                matchId, request.objective(), winnerId);
        if (confirmations >= 2) {
            TeamSide winner = players.findByMatchIdAndPlayerId(matchId, winnerId)
                    .orElseThrow(() -> conflict("Winner is not in this duel")).team();
            matchService.recordVerifiedDuelResult(matchId, token.playerId(), winner);
            challenge.complete(now);
            tokens.findAllByMatchId(matchId).forEach(value -> value.revoke(now));
            realtime.publishAfterCommit(List.of(challenge.challengerId(), challenge.opponentId()),
                    "DUEL_RESULT_VERIFIED");
            return new WatcherObservationResponse("VERIFIED", request.objective(), winnerId);
        }
        realtime.publishAfterCommit(List.of(challenge.challengerId(), challenge.opponentId()),
                "DUEL_OBSERVATION_PENDING");
        return new WatcherObservationResponse("WAITING_FOR_SECOND_WATCHER", request.objective(), winnerId);
    }

    private DuelWatcherToken authenticate(String rawToken, UUID matchId) {
        if (rawToken == null || rawToken.isBlank()) throw unauthorized("Watcher token is missing");
        DuelWatcherToken token = tokens.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> unauthorized("Watcher token is invalid"));
        if (!token.matchId().equals(matchId) || !token.active(clock.instant()))
            throw unauthorized("Watcher token is expired or does not belong to this duel");
        return token;
    }

    private GameMatch duelMatch(UUID matchId) {
        GameMatch match = matches.findById(matchId).orElseThrow(() -> notFound("Match not found"));
        if (!"ONE_V_ONE".equals(match.mode()) || match.status() != MatchStatus.CONFIRMED)
            throw conflict("Duel lobby is not active");
        return match;
    }

    private static UUID playerForRiotId(DuelChallenge challenge, String riotId) {
        String normalized = riotId == null ? "" : riotId.trim();
        if (challenge.challengerRiotId().equalsIgnoreCase(normalized)) return challenge.challengerId();
        if (challenge.opponentRiotId().equalsIgnoreCase(normalized)) return challenge.opponentId();
        throw bad("Reported Riot ID is not part of this duel");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static ResponseStatusException bad(String value) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, value); }
    private static ResponseStatusException conflict(String value) { return new ResponseStatusException(HttpStatus.CONFLICT, value); }
    private static ResponseStatusException forbidden(String value) { return new ResponseStatusException(HttpStatus.FORBIDDEN, value); }
    private static ResponseStatusException unauthorized(String value) { return new ResponseStatusException(HttpStatus.UNAUTHORIZED, value); }
    private static ResponseStatusException notFound(String value) { return new ResponseStatusException(HttpStatus.NOT_FOUND, value); }
}
