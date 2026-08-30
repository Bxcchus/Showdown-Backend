package lol.pinkward.showdown.player;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RiotLinkController {

    private final RiotLinkService links;

    RiotLinkController(RiotLinkService links) {
        this.links = links;
    }

    @PostMapping("/api/v2/players/me/riot-link-challenges")
    @PreAuthorize("hasAuthority('SCOPE_profile:write')")
    ResponseEntity<RiotLinkChallengeResponse> issue(@AuthenticationPrincipal Jwt jwt) {
        RiotLinkChallengeResponse response = links.issue(
                UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("preferred_username"));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(response);
    }

    @PostMapping("/api/v2/players/me/riot-link-challenges/{challengeId}/complete")
    @PreAuthorize("hasAuthority('SCOPE_profile:write')")
    ResponseEntity<PlayerProfileSnapshot> complete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID challengeId,
            @Valid @RequestBody VerifiedRiotIdentityRequest identity) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(links.complete(challengeId, UUID.fromString(jwt.getSubject()), identity));
    }
}
