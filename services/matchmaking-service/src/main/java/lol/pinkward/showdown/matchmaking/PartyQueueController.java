package lol.pinkward.showdown.matchmaking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/matchmaking/parties")
@PreAuthorize("hasAuthority('SCOPE_service:queue:party')")
class PartyQueueController {

    private final QueueService queue;

    PartyQueueController(QueueService queue) {
        this.queue = queue;
    }

    @PostMapping("/{partyId}")
    @ResponseStatus(HttpStatus.CREATED)
    List<QueueSnapshot> join(
            @PathVariable UUID partyId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody JoinPartyQueueRequest request) {
        return queue.joinParty(
                partyId,
                request.region(),
                request.members().stream()
                        .map(member -> new QueueService.PartyQueueMember(
                                member.playerId(), member.primaryRole(), member.secondaryRole(), member.simulated()))
                        .toList(),
                idempotencyKey);
    }

    @DeleteMapping("/{partyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@PathVariable UUID partyId) {
        queue.leaveParty(partyId);
    }

    record JoinPartyQueueRequest(
            @NotBlank String region,
            @NotNull @Size(min = 1, max = 5) List<@Valid PartyMemberRequest> members) {}

    record PartyMemberRequest(
            @NotNull UUID playerId,
            @NotBlank String primaryRole,
            @NotBlank String secondaryRole,
            boolean simulated) {}
}
