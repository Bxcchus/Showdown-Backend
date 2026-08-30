package lol.pinkward.showdown.match;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/watchers/duels/{matchId}")
@PreAuthorize("hasAuthority('SCOPE_service:duel:observe')")
class DuelWatcherController {
    private final DuelWatcherService watchers;
    DuelWatcherController(DuelWatcherService watchers) { this.watchers = watchers; }

    @GetMapping WatcherAssignment assignment(@PathVariable UUID matchId,
            @RequestHeader("X-Watcher-Token") String watcherToken) {
        return watchers.assignment(watcherToken, matchId);
    }
    @PutMapping("/state") WatcherStateResponse state(@PathVariable UUID matchId,
            @RequestHeader("X-Watcher-Token") String watcherToken,
            @Valid @RequestBody WatcherStateRequest request) {
        return watchers.updateState(watcherToken, matchId, request.state());
    }
    @PostMapping("/observations") WatcherObservationResponse observe(@PathVariable UUID matchId,
            @RequestHeader("X-Watcher-Token") String watcherToken,
            @Valid @RequestBody WatcherObservationRequest request) {
        return watchers.observe(watcherToken, matchId, request);
    }
}
