package lol.pinkward.showdown.match;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/watchers/matches/{matchId}")
@PreAuthorize("hasAuthority('SCOPE_service:match:observe')")
class TeamMatchWatcherController {
    private final TeamMatchWatcherService watchers;

    TeamMatchWatcherController(TeamMatchWatcherService watchers) { this.watchers = watchers; }

    @GetMapping
    TeamWatcherAssignment assignment(@PathVariable UUID matchId,
            @RequestHeader("X-Watcher-Token") String watcherToken) {
        return watchers.assignment(watcherToken, matchId);
    }

    @PutMapping("/state")
    WatcherStateResponse state(@PathVariable UUID matchId,
            @RequestHeader("X-Watcher-Token") String watcherToken,
            @Valid @RequestBody WatcherStateRequest request) {
        return watchers.updateState(watcherToken, matchId, request.state());
    }

    @PostMapping("/result")
    TeamWatcherResultResponse result(@PathVariable UUID matchId,
            @RequestHeader("X-Watcher-Token") String watcherToken,
            @Valid @RequestBody TeamWatcherResultRequest request) {
        return watchers.observeResult(watcherToken, matchId, request);
    }

    @PostMapping("/cancel")
    void cancel(@PathVariable UUID matchId,
            @RequestHeader("X-Watcher-Token") String watcherToken) {
        watchers.cancel(watcherToken, matchId);
    }
}
