package lol.pinkward.showdown.match;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v2/matches")
class MatchController {

    private final MatchApplicationService matches;
    private final BotMatchResultService botResults;
    private final TeamMatchWatcherService teamWatchers;

    MatchController(MatchApplicationService matches, BotMatchResultService botResults,
            TeamMatchWatcherService teamWatchers) {
        this.matches = matches;
        this.botResults = botResults;
        this.teamWatchers = teamWatchers;
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    MatchSnapshot current(@AuthenticationPrincipal Jwt jwt) {
        return matches.current(playerId(jwt));
    }

    @GetMapping("/current-lobby")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    MatchSnapshot currentLobby(@AuthenticationPrincipal Jwt jwt) {
        return matches.currentLobby(playerId(jwt));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    MatchHistoryPage history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String outcome) {
        return matches.history(
                playerId(jwt),
                Math.max(0, page),
                Math.min(25, Math.max(1, size)),
                optionalRegion(region),
                optionalMode(mode),
                optionalRole(role),
                optionalOutcome(outcome));
    }

    @GetMapping("/history/{matchId}")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    MatchDetail detail(@PathVariable UUID matchId, @AuthenticationPrincipal Jwt jwt) {
        return matches.detail(matchId, playerId(jwt));
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    MatchStatistics statistics(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "EUW") String region) {
        return matches.statistics(playerId(jwt), normalizeRegion(region));
    }

    @GetMapping("/leaderboard")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    LeaderboardSnapshot leaderboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "EUW") String region,
            @RequestParam(defaultValue = "50") int limit) {
        return matches.leaderboard(playerId(jwt), normalizeRegion(region), Math.min(100, Math.max(1, limit)));
    }

    @GetMapping("/seasons")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    java.util.List<RatingSeasonSnapshot> seasons() {
        return matches.ratingSeasons();
    }

    @GetMapping("/duel/statistics")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    DuelStatistics duelStatistics(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "EUW") String region) {
        return matches.duelStatistics(playerId(jwt), normalizeRegion(region));
    }

    @GetMapping("/duel/leaderboard")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    DuelLeaderboardSnapshot duelLeaderboard(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "EUW") String region,
            @RequestParam(defaultValue = "50") int limit) {
        return matches.duelLeaderboard(playerId(jwt), normalizeRegion(region), Math.min(100, Math.max(1, limit)));
    }

    @PostMapping("/{matchId}/ready")
    @PreAuthorize("hasAuthority('SCOPE_match:ready')")
    MatchSnapshot ready(
            @PathVariable UUID matchId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ReadyAnswer answer) {
        return matches.answer(matchId, playerId(jwt), answer.accepted());
    }

    @PostMapping("/{matchId}/watcher-token")
    @PreAuthorize("hasAuthority('SCOPE_match:read')")
    ResponseEntity<WatcherTokenResponse> watcherToken(
            @PathVariable UUID matchId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(teamWatchers.issue(matchId, playerId(jwt)));
    }

    @PostMapping("/{matchId}/result")
    @PreAuthorize("hasAuthority('SCOPE_service:match:result')")
    MatchSnapshot result(
            @PathVariable UUID matchId,
            @Valid @RequestBody MatchResultRequest result) {
        return matches.recordTrustedResult(matchId, result.winningTeam());
    }

    @GetMapping("/{matchId}/bot-assignment")
    @PreAuthorize("hasAuthority('SCOPE_service:match:bot-result')")
    ResponseEntity<BotMatchAssignment> botAssignment(@PathVariable UUID matchId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(botResults.assignment(matchId));
    }

    @PostMapping("/{matchId}/bot-result")
    @PreAuthorize("hasAuthority('SCOPE_service:match:bot-result')")
    MatchSnapshot botResult(
            @PathVariable UUID matchId,
            @Valid @RequestBody BotMatchResultRequest result) {
        return botResults.record(matchId, result);
    }

    private static UUID playerId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Token subject is not a player UUID",
                    exception);
        }
    }

    private static String normalizeRegion(String region) {
        String normalized = region == null ? "" : region.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("EUW", "EUNE", "NA").contains(normalized)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported region");
        }
        return normalized;
    }

    private static String optionalRegion(String region) {
        return region == null || region.isBlank() ? null : normalizeRegion(region);
    }

    private static LaneRole optionalRole(String role) {
        if (role == null || role.isBlank()) return null;
        try {
            return LaneRole.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported role");
        }
    }

    private static String optionalMode(String mode) {
        if (mode == null || mode.isBlank()) return null;
        String normalized = mode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ONE_V_ONE", "FIVE_V_FIVE").contains(normalized)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported mode");
        }
        return normalized;
    }

    private static String optionalOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) return null;
        String normalized = outcome.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("VICTORY", "DEFEAT").contains(normalized)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported outcome");
        }
        return normalized;
    }
}
