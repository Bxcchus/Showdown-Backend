package lol.pinkward.showdown.player;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/players")
class PlayerInternalController {

    private final PlayerProfileRepository profiles;

    PlayerInternalController(PlayerProfileRepository profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/{playerId}/duel-identity")
    @PreAuthorize("hasAuthority('SCOPE_service:profile:read')")
    ResponseEntity<DuelIdentity> duelIdentity(@PathVariable UUID playerId) {
        PlayerProfile profile = profiles.findById(playerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found"));
        if (profile.riotPuuid() == null || profile.riotGameName() == null || profile.riotTagLine() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Player has no verified Riot identity");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new DuelIdentity(
                        profile.playerId(),
                        profile.riotPuuid(),
                        profile.riotGameName() + "#" + profile.riotTagLine(),
                        profile.region()));
    }

    record DuelIdentity(UUID playerId, String puuid, String riotId, String region) {}
}
