package lol.pinkward.showdown.match;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class TeamMatchWatcherService {
    private static final Duration MAX_RESULT_AGE = Duration.ofMinutes(5);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Set<String> STATES = Set.of(
            "ISSUED", "LCU_CONNECTED", "LOBBY_CREATED", "INVITES_SENT", "JOINING", "JOINED",
            "ROSTER_VERIFIED", "CHAMP_SELECT_STARTED", "IN_GAME", "RESULT_RECORDED",
            "REVIEW_REQUIRED", "ERROR");
    private static final Set<String> CANCELLABLE_STATES = Set.of(
            "LCU_CONNECTED", "LOBBY_CREATED", "INVITES_SENT", "JOINING", "JOINED",
            "ROSTER_VERIFIED", "CHAMP_SELECT_STARTED");

    private final DuelWatcherTokenRepository tokens;
    private final TeamMatchWatcherResultRepository results;
    private final GameMatchRepository matches;
    private final MatchPlayerRepository players;
    private final LobbyCredentialService credentials;
    private final MatchApplicationService matchService;
    private final MatchRealtimeHub realtime;
    private final DuelIdentityClient identities;
    private final Duration tokenTtl;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    TeamMatchWatcherService(
            DuelWatcherTokenRepository tokens,
            TeamMatchWatcherResultRepository results,
            GameMatchRepository matches,
            MatchPlayerRepository players,
            LobbyCredentialService credentials,
            MatchApplicationService matchService,
            MatchRealtimeHub realtime,
            DuelIdentityClient identities,
            @Value("${pinkward.match.watcher-token-ttl:4h}") Duration tokenTtl) {
        this.tokens = tokens;
        this.results = results;
        this.matches = matches;
        this.players = players;
        this.credentials = credentials;
        this.matchService = matchService;
        this.realtime = realtime;
        this.identities = identities;
        this.tokenTtl = tokenTtl;
    }

    @Transactional
    WatcherTokenResponse issue(UUID matchId, UUID playerId) {
        GameMatch match = teamMatch(matchId);
        List<MatchPlayer> roster = roster(matchId);
        MatchPlayer player = roster.stream()
                .filter(candidate -> candidate.playerId().equals(playerId) && !candidate.bot())
                .findFirst()
                .orElseThrow(() -> forbidden("Player is not a human participant in this match"));
        UUID coordinator = roster.stream().filter(candidate -> !candidate.bot())
                .map(MatchPlayer::playerId).min(Comparator.comparing(UUID::toString))
                .orElseThrow(() -> conflict("The match has no human participant"));
        String role = coordinator.equals(player.playerId()) ? "HOST" : "GUEST";
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(tokenTtl);
        DuelWatcherToken value = tokens.findByMatchIdAndPlayerId(match.id(), playerId).orElse(null);
        if (value == null) value = DuelWatcherToken.issue(match.id(), playerId, hash(token), role, now, expiresAt);
        else value.rotate(hash(token), now, expiresAt);
        tokens.save(value);
        realtime.publishAfterCommit(List.of(playerId), "TEAM_WATCHER_TOKEN_ISSUED");
        return new WatcherTokenResponse(token, matchId, role, expiresAt);
    }

    @Transactional
    TeamWatcherAssignment assignment(String rawToken, UUID matchId) {
        DuelWatcherToken token = authenticate(rawToken, matchId);
        GameMatch match = teamMatch(matchId);
        List<MatchPlayer> roster = roster(matchId);
        MatchPlayer own = roster.stream().filter(player -> player.playerId().equals(token.playerId()) && !player.bot())
                .findFirst().orElseThrow(() -> forbidden("Watcher player is not in this match"));
        Map<UUID, DuelIdentityClient.DuelIdentity> resolved = new HashMap<>();
        for (MatchPlayer player : roster) {
            if (!player.bot()) resolved.put(player.playerId(), identities.resolve(player.playerId()));
        }
        DuelIdentityClient.DuelIdentity ownIdentity = resolved.get(own.playerId());
        List<TeamWatcherPlayer> expected = roster.stream().map(player -> {
            DuelIdentityClient.DuelIdentity identity = resolved.get(player.playerId());
            return new TeamWatcherPlayer(
                    player.playerId(), identity == null ? null : identity.puuid(),
                    identity == null ? null : identity.riotId(), player.team().name(),
                    player.assignedRole().name(), player.bot());
        }).toList();
        token.touch(token.state(), clock.instant());
        return new TeamWatcherAssignment(
                match.id(), token.role(), own.playerId(), ownIdentity.puuid(), ownIdentity.riotId(),
                own.team().name(), match.region(), match.lobbyName(),
                credentials.decrypt(match.lobbyPasswordEncrypted()), expected, token.state(), token.expiresAt());
    }

    @Transactional
    WatcherStateResponse updateState(String rawToken, UUID matchId, String requestedState) {
        String state = requestedState == null ? "" : requestedState.trim().toUpperCase(Locale.ROOT);
        if (!STATES.contains(state)) throw bad("Unsupported team watcher state");
        DuelWatcherToken token = authenticate(rawToken, matchId);
        teamMatch(matchId);
        token.touch(state, clock.instant());
        realtime.publishAfterCommit(List.of(token.playerId()), "TEAM_WATCHER_STATE");
        return new WatcherStateResponse(matchId, state, token.expiresAt());
    }

    @Transactional
    TeamWatcherResultResponse observeResult(
            String rawToken, UUID matchId, TeamWatcherResultRequest request) {
        DuelWatcherToken token = authenticate(rawToken, matchId);
        GameMatch match = teamMatch(matchId);
        List<MatchPlayer> roster = roster(matchId);
        MatchPlayer reporter = roster.stream().filter(player -> player.playerId().equals(token.playerId()) && !player.bot())
                .findFirst().orElseThrow(() -> forbidden("Watcher player is not in this match"));
        Instant now = clock.instant();
        if (request.observedAt().isBefore(now.minus(MAX_RESULT_AGE))
                || request.observedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw bad("Watcher result is outside the accepted time window");
        }
        String gameId = request.gameId().trim();
        if (gameId.length() > 64) throw bad("Game ID is too long");
        Set<String> expectedPuuids = roster.stream().filter(player -> !player.bot())
                .map(player -> identities.resolve(player.playerId()).puuid().trim())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> verifiedPuuids = request.verifiedPuuids().stream().map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
        if (!verifiedPuuids.equals(expectedPuuids)) {
            throw conflict("Verified League roster does not match the assigned human roster");
        }
        recordChampions(roster, expectedPuuids, request.champions());
        TeamMatchWatcherResult prior = results.findByMatchIdAndReporterId(matchId, reporter.playerId()).orElse(null);
        if (prior != null && (prior.winningTeam() != request.winningTeam()
                || !prior.gameId().equals(gameId))) {
            throw conflict("This watcher already reported another result");
        }
        if (prior == null) results.save(TeamMatchWatcherResult.observed(
                matchId, reporter.playerId(), reporter.team(), request.winningTeam(),
                gameId, request.observedAt(), now));
        token.touch("RESULT_RECORDED", now);

        List<TeamMatchWatcherResult> reports = results.findAllByMatchId(matchId);
        boolean conflicting = reports.stream().anyMatch(report -> report.winningTeam() != request.winningTeam()
                || !report.gameId().equals(gameId));
        if (conflicting) {
            tokens.findAllByMatchId(matchId).forEach(value -> value.touch("REVIEW_REQUIRED", now));
            publish(roster, "TEAM_RESULT_REVIEW_REQUIRED");
            return new TeamWatcherResultResponse("REVIEW_REQUIRED", request.winningTeam(), gameId);
        }

        Set<TeamSide> requiredReporterTeams = roster.stream().filter(player -> !player.bot())
                .map(MatchPlayer::team).collect(Collectors.toSet());
        Set<TeamSide> confirmedReporterTeams = reports.stream().map(TeamMatchWatcherResult::reporterTeam)
                .collect(Collectors.toSet());
        if (!confirmedReporterTeams.containsAll(requiredReporterTeams)) {
            publish(roster, "TEAM_RESULT_PENDING");
            return new TeamWatcherResultResponse("WAITING_FOR_OTHER_TEAM", request.winningTeam(), gameId);
        }

        matchService.recordVerifiedTeamResult(match.id(), reporter.playerId(), request.winningTeam());
        tokens.findAllByMatchId(matchId).forEach(value -> value.revoke(now));
        publish(roster, "TEAM_RESULT_VERIFIED");
        return new TeamWatcherResultResponse("VERIFIED", request.winningTeam(), gameId);
    }

    @Transactional
    void cancel(String rawToken, UUID matchId) {
        DuelWatcherToken token = authenticate(rawToken, matchId);
        teamMatch(matchId);
        if (!CANCELLABLE_STATES.contains(token.state())) {
            throw conflict("The 5v5 lobby can only be cancelled before the game starts");
        }
        matchService.cancelVerifiedTeamMatch(matchId, token.playerId());
        Instant now = clock.instant();
        tokens.findAllByMatchId(matchId).forEach(value -> value.revoke(now));
    }

    private DuelWatcherToken authenticate(String rawToken, UUID matchId) {
        if (rawToken == null || rawToken.isBlank()) throw unauthorized("Watcher token is missing");
        DuelWatcherToken token = tokens.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> unauthorized("Watcher token is invalid"));
        if (!token.matchId().equals(matchId) || !token.active(clock.instant()))
            throw unauthorized("Watcher token is expired or does not belong to this match");
        return token;
    }

    private GameMatch teamMatch(UUID matchId) {
        GameMatch match = matches.findById(matchId).orElseThrow(() -> notFound("Match not found"));
        if (!"FIVE_V_FIVE".equals(match.mode()) || match.status() != MatchStatus.CONFIRMED)
            throw conflict("5v5 lobby is not active");
        return match;
    }

    private List<MatchPlayer> roster(UUID matchId) {
        List<MatchPlayer> roster = players.findByMatchIdOrderByTeamAscPlayerIdAsc(matchId);
        if (roster.size() != 10 || roster.stream().filter(player -> player.team() == TeamSide.BLUE).count() != 5
                || roster.stream().filter(player -> player.team() == TeamSide.RED).count() != 5) {
            throw conflict("A rated 5v5 match requires two complete teams");
        }
        return roster;
    }

    private void recordChampions(
            List<MatchPlayer> roster,
            Set<String> expectedPuuids,
            List<TeamWatcherChampion> champions) {
        if (champions == null || champions.isEmpty()) return;
        Map<String, String> byPuuid = new HashMap<>();
        for (TeamWatcherChampion champion : champions) {
            String prior = byPuuid.put(champion.puuid().trim(), champion.championName().trim());
            if (prior != null) throw conflict("Duplicate champion entry for a League player");
        }
        if (!byPuuid.keySet().equals(expectedPuuids)) {
            throw conflict("Champion roster does not match the verified League roster");
        }
        roster.stream().filter(player -> !player.bot()).forEach(player -> {
            String puuid = identities.resolve(player.playerId()).puuid().trim();
            player.recordChampion(byPuuid.get(puuid));
        });
    }

    private void publish(List<MatchPlayer> roster, String type) {
        realtime.publishAfterCommit(roster.stream().filter(player -> !player.bot())
                .map(MatchPlayer::playerId).toList(), type);
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
