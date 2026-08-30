package lol.pinkward.showdown.player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PartyMemberRepository extends JpaRepository<PartyMember, UUID> {
    Optional<PartyMember> findByPlayerId(UUID playerId);
    List<PartyMember> findByPartyIdOrderByJoinedAtAscIdAsc(UUID partyId);
    long countByPartyId(UUID partyId);
    void deleteByPartyIdAndPlayerId(UUID partyId, UUID playerId);
}
