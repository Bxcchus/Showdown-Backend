package lol.pinkward.showdown.player;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v2/players/directory")
@PreAuthorize("hasAuthority('SCOPE_profile:read')")
class PlayerDirectoryController {
    private final PlayerProfileRepository profiles;
    PlayerDirectoryController(PlayerProfileRepository profiles) { this.profiles = profiles; }

    @GetMapping
    List<Entry> find(@RequestParam List<UUID> ids) {
        if (ids.size() > 100) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "At most 100 players can be resolved");
        return profiles.findAllById(ids).stream()
                .map(Entry::from)
                .toList();
    }

    @GetMapping("/search")
    Entry search(@RequestParam String displayName) {
        return profiles.findByDisplayNameIgnoreCase(displayName.trim())
                .map(Entry::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Player not found"));
    }

    record Entry(UUID playerId, String displayName, boolean riotLinked) {
        static Entry from(PlayerProfile profile) {
            return new Entry(profile.playerId(), profile.displayName(),
                    profile.riotPuuid() != null && profile.riotGameName() != null && profile.riotTagLine() != null);
        }
    }
}
