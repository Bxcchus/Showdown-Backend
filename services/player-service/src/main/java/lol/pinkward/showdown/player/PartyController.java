package lol.pinkward.showdown.player;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/parties")
@PreAuthorize("hasAuthority('SCOPE_party:manage')")
class PartyController {

    private final PartyService parties;

    PartyController(PartyService parties) {
        this.parties = parties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PartySnapshot create(@AuthenticationPrincipal Jwt jwt) {
        return parties.create(playerId(jwt), jwt.getClaimAsString("preferred_username"));
    }

    @GetMapping("/current")
    PartySnapshot current(@AuthenticationPrincipal Jwt jwt) {
        return parties.current(playerId(jwt));
    }

    @PutMapping("/current")
    PartySnapshot updateRegion(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegionRequest request) {
        return parties.updateRegion(playerId(jwt), request.region());
    }

    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@AuthenticationPrincipal Jwt jwt) {
        parties.leave(playerId(jwt));
    }

    @PostMapping("/current/invitations")
    PartySnapshot invite(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InviteRequest request) {
        return parties.invite(playerId(jwt), request.displayName());
    }

    @GetMapping("/invitations")
    List<PartyInvitationView> invitations(@AuthenticationPrincipal Jwt jwt) {
        return parties.invitations(playerId(jwt));
    }

    @PostMapping("/invitations/{invitationId}/accept")
    PartySnapshot accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID invitationId) {
        return parties.accept(playerId(jwt), invitationId);
    }

    @PostMapping("/invitations/{invitationId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID invitationId) {
        parties.decline(playerId(jwt), invitationId);
    }

    @PutMapping("/current/ready")
    PartySnapshot ready(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReadyRequest request) {
        return parties.ready(playerId(jwt), request.ready());
    }

    @PutMapping("/current/members/{memberId}/ready")
    PartySnapshot simulatedReady(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID memberId,
            @Valid @RequestBody ReadyRequest request) {
        return parties.setSimulatedReady(playerId(jwt), memberId, request.ready());
    }

    @DeleteMapping("/current/members/{memberId}")
    PartySnapshot removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID memberId) {
        return parties.removeMember(playerId(jwt), memberId);
    }

    @PostMapping("/current/search")
    PartySnapshot startSearch(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return parties.startSearch(playerId(jwt), idempotencyKey);
    }

    @DeleteMapping("/current/search")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void stopSearch(@AuthenticationPrincipal Jwt jwt) {
        parties.stopSearch(playerId(jwt));
    }

    private static UUID playerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    record InviteRequest(@NotBlank String displayName) {}
    record RegionRequest(@NotBlank String region) {}
    record ReadyRequest(@NotNull Boolean ready) {}
}
