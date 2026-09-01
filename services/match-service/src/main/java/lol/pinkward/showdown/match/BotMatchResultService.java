package lol.pinkward.showdown.match;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class BotMatchResultService {

    private static final Duration MAX_RESULT_AGE = Duration.ofMinutes(2);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(30);

    private final GameMatchRepository matches;
    private final MatchPlayerRepository players;
    private final LobbyCredentialService credentials;
    private final MatchApplicationService matchService;
    private final boolean enabled;

    BotMatchResultService(
            GameMatchRepository matches,
            MatchPlayerRepository players,
            LobbyCredentialService credentials,
            MatchApplicationService matchService,
            @Value("${pinkward.match.local-bot-results-enabled:false}") boolean enabled) {
        this.matches = matches;
        this.players = players;
        this.credentials = credentials;
        this.matchService = matchService;
        this.enabled = enabled;
    }

    @Transactional(readOnly = true)
    BotMatchAssignment assignment(UUID matchId) {
        BotRoster roster = activeBotDuel(matchId);
        return new BotMatchAssignment(
                roster.match().id(),
                roster.match().lobbyName(),
                credentials.decrypt(roster.match().lobbyPasswordEncrypted()));
    }

    @Transactional
    MatchSnapshot record(UUID matchId, BotMatchResultRequest request) {
        Instant now = Instant.now();
        if (request.observedAt().isBefore(now.minus(MAX_RESULT_AGE))
                || request.observedAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Bot result observation is outside the accepted time window");
        }
        BotRoster roster = activeBotDuel(matchId);
        roster.human().recordChampion(request.championName());
        TeamSide winner = request.humanWon() ? roster.human().team() : roster.bot().team();
        return matchService.recordTrustedResult(matchId, winner);
    }

    @Transactional
    MatchSnapshot cancel(UUID matchId) {
        activeBotDuel(matchId);
        return matchService.cancelVerifiedBotMatch(matchId);
    }

    private BotRoster activeBotDuel(UUID matchId) {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Local bot result ingestion is disabled");
        }
        GameMatch match = matches.findById(matchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found"));
        if (!"ONE_V_ONE".equals(match.mode()) || match.status() != MatchStatus.CONFIRMED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bot duel lobby is not active");
        }
        List<MatchPlayer> roster = players.findByMatchIdOrderByTeamAscPlayerIdAsc(matchId);
        List<MatchPlayer> humans = roster.stream().filter(player -> !player.bot()).toList();
        List<MatchPlayer> bots = roster.stream().filter(MatchPlayer::bot).toList();
        if (roster.size() != 2 || humans.size() != 1 || bots.size() != 1
                || humans.getFirst().team() == bots.getFirst().team()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Expected exactly one human and one bot on opposite teams");
        }
        return new BotRoster(match, humans.getFirst(), bots.getFirst());
    }

    private record BotRoster(GameMatch match, MatchPlayer human, MatchPlayer bot) {}
}
