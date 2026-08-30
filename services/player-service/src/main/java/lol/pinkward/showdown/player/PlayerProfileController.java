package lol.pinkward.showdown.player;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/players/me")
class PlayerProfileController {

    private final PlayerProfileService profiles;

    PlayerProfileController(PlayerProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_profile:read')")
    PlayerProfileSnapshot me(@AuthenticationPrincipal Jwt jwt) {
        return profiles.me(playerId(jwt), username(jwt));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SCOPE_profile:write')")
    PlayerProfileSnapshot update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdatePlayerProfileRequest request) {
        return profiles.update(
                playerId(jwt),
                username(jwt),
                request.displayName(),
                request.region(),
                request.primaryRole(),
                request.secondaryRole());
    }

    @PostMapping("/presence")
    @PreAuthorize("hasAuthority('SCOPE_profile:write')")
    PlayerProfileSnapshot heartbeat(@AuthenticationPrincipal Jwt jwt) {
        return profiles.heartbeat(playerId(jwt), username(jwt));
    }

    @DeleteMapping("/presence")
    @PreAuthorize("hasAuthority('SCOPE_profile:write')")
    PlayerProfileSnapshot offline(@AuthenticationPrincipal Jwt jwt) {
        return profiles.offline(playerId(jwt), username(jwt));
    }

    private static UUID playerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static String username(Jwt jwt) {
        return jwt.getClaimAsString("preferred_username");
    }

    record UpdatePlayerProfileRequest(
            @NotBlank @Size(min = 3, max = 24) String displayName,
            @NotBlank String region,
            @NotBlank String primaryRole,
            @NotBlank String secondaryRole) {}

}
