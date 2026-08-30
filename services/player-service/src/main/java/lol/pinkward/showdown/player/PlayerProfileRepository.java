package lol.pinkward.showdown.player;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlayerProfileRepository extends JpaRepository<PlayerProfile, UUID> {

    boolean existsByDisplayNameIgnoreCaseAndPlayerIdNot(String displayName, UUID playerId);

    Optional<PlayerProfile> findByDisplayNameIgnoreCase(String displayName);

    Optional<PlayerProfile> findByRiotPuuid(String riotPuuid);

    Optional<PlayerProfile> findByRiotGameNameIgnoreCaseAndRiotTagLineIgnoreCase(
            String gameName, String tagLine);
}
