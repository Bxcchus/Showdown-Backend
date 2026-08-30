package lol.pinkward.showdown.matchmaking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/matchmaking/queue")
@PreAuthorize("hasAuthority('SCOPE_queue:write')")
class QueueController {

    private final QueueService queue;

    QueueController(QueueService queue) {
        this.queue = queue;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    QueueSnapshot join(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody JoinQueueRequest request) {
        return queue.join(
                playerId(jwt), request.region(), request.mode(), request.primaryRole(), request.secondaryRole(), idempotencyKey);
    }

    @GetMapping
    QueueSnapshot status(@AuthenticationPrincipal Jwt jwt) {
        return queue.status(playerId(jwt));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@AuthenticationPrincipal Jwt jwt) {
        queue.leave(playerId(jwt));
    }

    private static UUID playerId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Token subject is not a player UUID", exception);
        }
    }

    record JoinQueueRequest(
            @NotBlank String region,
            String mode,
            @NotBlank String primaryRole,
            @NotBlank String secondaryRole) {}
}
