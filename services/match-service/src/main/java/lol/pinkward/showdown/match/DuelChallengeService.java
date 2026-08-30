package lol.pinkward.showdown.match;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class DuelChallengeService {
    private final DuelChallengeRepository challenges;
    private final GameMatchRepository matches;
    private final MatchPlayerRepository players;
    private final LobbyCredentialService credentials;
    private final MatchRealtimeHub realtime;
    private final DuelIdentityClient identities;
    private final Duration ttl;
    private final Clock clock = Clock.systemUTC();

    DuelChallengeService(DuelChallengeRepository challenges, GameMatchRepository matches,
            MatchPlayerRepository players, LobbyCredentialService credentials, MatchRealtimeHub realtime,
            DuelIdentityClient identities,
            @Value("${pinkward.duel.challenge-ttl:10m}") Duration ttl) {
        this.challenges = challenges; this.matches = matches; this.players = players;
        this.credentials = credentials; this.realtime = realtime; this.identities = identities; this.ttl = ttl;
    }

    @Transactional
    DuelChallengeSnapshot create(UUID challenger, CreateDuelChallengeRequest request) {
        if (challenger.equals(request.opponentId())) throw bad("You cannot challenge yourself");
        Instant now = clock.instant();
        DuelIdentityClient.DuelIdentity challengerIdentity = identities.resolve(challenger);
        DuelIdentityClient.DuelIdentity opponentIdentity = identities.resolve(request.opponentId());
        if (!challengerIdentity.region().equals(opponentIdentity.region())) {
            throw bad("Both players must belong to the same region");
        }
        if (challenges.hasPendingForEither(challenger, request.opponentId(), DuelChallengeStatus.PENDING, now))
            throw conflict("One of the players already has a pending duel invitation");
        if (matches.findActiveForPlayer(challenger).isPresent() || matches.findActiveForPlayer(request.opponentId()).isPresent())
            throw conflict("One of the players already has an active match");
        DuelChallenge value = challenges.save(DuelChallenge.pending(challenger, request.opponentId(),
                riotId(challengerIdentity.riotId()), riotId(opponentIdentity.riotId()),
                region(challengerIdentity.region()), now, now.plus(ttl)));
        realtime.publishAfterCommit(List.of(challenger, request.opponentId()), "DUEL_INVITATION");
        return DuelChallengeSnapshot.from(value, challenger);
    }

    @Transactional(readOnly = true)
    List<DuelChallengeSnapshot> list(UUID playerId) {
        return challenges.findForPlayer(playerId).stream().limit(25)
                .map(value -> DuelChallengeSnapshot.from(value, playerId)).toList();
    }

    @Transactional
    DuelChallengeSnapshot accept(UUID challengeId, UUID playerId) {
        DuelChallenge value = pending(challengeId);
        if (!value.opponentId().equals(playerId)) throw forbidden();
        if (matches.findActiveForPlayer(value.challengerId()).isPresent() || matches.findActiveForPlayer(value.opponentId()).isPresent())
            throw conflict("One of the players already has an active match");
        Instant now = clock.instant();
        GameMatch match = matches.save(GameMatch.readyCheck(UUID.randomUUID(), value.region(), "ONE_V_ONE", now, now.plusSeconds(45)));
        LobbyCredentialService.LobbyCredentials lobby = credentials.create(match.id());
        match.confirm(lobby.name(), lobby.encryptedPassword());
        MatchPlayer host = MatchPlayer.readyCheck(match.id(), value.challengerId(), TeamSide.BLUE, false, LaneRole.MID);
        MatchPlayer guest = MatchPlayer.readyCheck(match.id(), value.opponentId(), TeamSide.RED, false, LaneRole.MID);
        host.answer(true); guest.answer(true); players.saveAll(List.of(host, guest));
        value.accept(match.id(), now);
        realtime.publishAfterCommit(List.of(value.challengerId(), value.opponentId()), "DUEL_ACCEPTED");
        return DuelChallengeSnapshot.from(value, playerId);
    }

    @Transactional
    DuelChallengeSnapshot decline(UUID challengeId, UUID playerId) {
        DuelChallenge value = pending(challengeId); if (!value.opponentId().equals(playerId)) throw forbidden();
        value.decline(clock.instant()); realtime.publishAfterCommit(List.of(value.challengerId(), value.opponentId()), "DUEL_DECLINED");
        return DuelChallengeSnapshot.from(value, playerId);
    }

    @Transactional
    DuelChallengeSnapshot cancel(UUID challengeId, UUID playerId) {
        DuelChallenge value = pending(challengeId); if (!value.challengerId().equals(playerId)) throw forbidden();
        value.cancel(clock.instant()); realtime.publishAfterCommit(List.of(value.challengerId(), value.opponentId()), "DUEL_CANCELLED");
        return DuelChallengeSnapshot.from(value, playerId);
    }

    @Scheduled(fixedDelay = 30000) @Transactional
    public void expire() { Instant now = clock.instant(); challenges.findByStatusAndExpiresAtBefore(DuelChallengeStatus.PENDING, now).forEach(value -> value.expire(now)); }

    private DuelChallenge pending(UUID id) {
        DuelChallenge value = challenges.findByIdForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Duel invitation not found"));
        if (value.status() != DuelChallengeStatus.PENDING || value.expiresAt().isBefore(clock.instant())) throw conflict("Duel invitation is closed");
        return value;
    }
    private static String region(String value) { String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT); if (!Set.of("EUW","EUNE","NA").contains(normalized)) throw bad("Unsupported region"); return normalized; }
    private static String riotId(String value) { String normalized = value == null ? "" : value.trim(); if (!normalized.matches("[^#]{3,16}#[A-Za-z0-9]{3,5}")) throw bad("A linked Riot ID is required"); return normalized; }
    private static ResponseStatusException bad(String value) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, value); }
    private static ResponseStatusException conflict(String value) { return new ResponseStatusException(HttpStatus.CONFLICT, value); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the invited player can perform this action"); }
}
