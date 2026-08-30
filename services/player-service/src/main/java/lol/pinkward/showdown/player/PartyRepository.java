package lol.pinkward.showdown.player;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PartyRepository extends JpaRepository<Party, UUID> {}
