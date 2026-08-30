package lol.pinkward.showdown.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "party_invitations")
class PartyInvitation {

    enum Status { PENDING, ACCEPTED, DECLINED, EXPIRED }

    @Id
    private UUID id;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "inviter_id", nullable = false)
    private UUID inviterId;

    @Column(name = "invitee_id", nullable = false)
    private UUID inviteeId;

    @Column(name = "invitee_display_name", nullable = false, length = 24)
    private String inviteeDisplayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected PartyInvitation() {}

    static PartyInvitation pending(
            UUID partyId,
            UUID inviterId,
            UUID inviteeId,
            String displayName,
            Instant now,
            Instant expiresAt) {
        PartyInvitation invitation = new PartyInvitation();
        invitation.id = UUID.randomUUID();
        invitation.partyId = partyId;
        invitation.inviterId = inviterId;
        invitation.inviteeId = inviteeId;
        invitation.inviteeDisplayName = displayName;
        invitation.status = Status.PENDING;
        invitation.createdAt = now;
        invitation.expiresAt = expiresAt;
        return invitation;
    }

    void accept(Instant now) { status = Status.ACCEPTED; respondedAt = now; }
    void decline(Instant now) { status = Status.DECLINED; respondedAt = now; }
    void expire(Instant now) { status = Status.EXPIRED; respondedAt = now; }
    boolean pendingAt(Instant now) { return status == Status.PENDING && expiresAt.isAfter(now); }
    UUID id() { return id; }
    UUID partyId() { return partyId; }
    UUID inviterId() { return inviterId; }
    UUID inviteeId() { return inviteeId; }
    String inviteeDisplayName() { return inviteeDisplayName; }
    Status status() { return status; }
    Instant expiresAt() { return expiresAt; }
}
