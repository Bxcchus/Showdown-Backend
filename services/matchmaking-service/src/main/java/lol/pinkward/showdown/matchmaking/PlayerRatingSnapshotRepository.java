package lol.pinkward.showdown.matchmaking;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlayerRatingSnapshotRepository extends JpaRepository<PlayerRatingSnapshot, UUID> {}
