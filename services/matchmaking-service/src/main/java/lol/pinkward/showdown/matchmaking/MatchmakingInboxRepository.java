package lol.pinkward.showdown.matchmaking;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MatchmakingInboxRepository extends JpaRepository<MatchmakingInboxEvent, UUID> {}
