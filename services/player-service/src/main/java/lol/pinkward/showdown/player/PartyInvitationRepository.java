package lol.pinkward.showdown.player;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PartyInvitationRepository extends JpaRepository<PartyInvitation, UUID> {
    List<PartyInvitation> findByInviteeIdAndStatusOrderByCreatedAtDesc(
            UUID inviteeId,
            PartyInvitation.Status status);
    List<PartyInvitation> findByPartyIdOrderByCreatedAtDesc(UUID partyId);
}
