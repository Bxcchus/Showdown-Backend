package lol.pinkward.showdown.match;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/matches/duels")
@PreAuthorize("hasAuthority('SCOPE_match:read')")
class DuelChallengeController {
    private final DuelChallengeService duels;
    private final DuelWatcherService watchers;
    DuelChallengeController(DuelChallengeService duels, DuelWatcherService watchers) {
        this.duels = duels; this.watchers = watchers;
    }
    @GetMapping List<DuelChallengeSnapshot> list(@AuthenticationPrincipal Jwt jwt) { return duels.list(playerId(jwt)); }
    @PostMapping DuelChallengeSnapshot create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateDuelChallengeRequest request) { return duels.create(playerId(jwt), request); }
    @PostMapping("/{id}/accept") DuelChallengeSnapshot accept(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return duels.accept(id, playerId(jwt)); }
    @PostMapping("/{id}/decline") DuelChallengeSnapshot decline(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return duels.decline(id, playerId(jwt)); }
    @PostMapping("/{id}/cancel") DuelChallengeSnapshot cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return duels.cancel(id, playerId(jwt)); }
    @PostMapping("/{matchId}/watcher-token") WatcherTokenResponse watcherToken(
            @PathVariable UUID matchId, @AuthenticationPrincipal Jwt jwt) {
        return watchers.issue(matchId, playerId(jwt));
    }
    private static UUID playerId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
