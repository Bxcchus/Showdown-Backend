package lol.pinkward.showdown.player;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "party_members")
class PartyMember {

    @Id
    private UUID id;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "player_id", nullable = false, unique = true)
    private UUID playerId;

    @Column(name = "display_name", nullable = false, length = 24)
    private String displayName;

    @Column(name = "primary_role", nullable = false, length = 16)
    private String primaryRole;

    @Column(name = "secondary_role", nullable = false, length = 16)
    private String secondaryRole;

    @Column(nullable = false)
    private boolean ready;

    @Column(nullable = false)
    private boolean simulated;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected PartyMember() {}

    static PartyMember real(UUID partyId, PlayerProfile profile, Instant now) {
        return create(
                partyId,
                profile.playerId(),
                profile.displayName(),
                profile.primaryRole(),
                profile.secondaryRole(),
                false,
                now);
    }

    static PartyMember simulated(
            UUID partyId,
            UUID playerId,
            String displayName,
            String primaryRole,
            String secondaryRole,
            Instant now) {
        return create(partyId, playerId, displayName, primaryRole, secondaryRole, true, now);
    }

    private static PartyMember create(
            UUID partyId,
            UUID playerId,
            String displayName,
            String primaryRole,
            String secondaryRole,
            boolean simulated,
            Instant now) {
        PartyMember member = new PartyMember();
        member.id = UUID.randomUUID();
        member.partyId = partyId;
        member.playerId = playerId;
        member.displayName = displayName;
        member.primaryRole = primaryRole;
        member.secondaryRole = secondaryRole;
        member.ready = false;
        member.simulated = simulated;
        member.joinedAt = now;
        return member;
    }

    void setReady(boolean ready) { this.ready = ready; }
    UUID partyId() { return partyId; }
    UUID playerId() { return playerId; }
    String displayName() { return displayName; }
    String primaryRole() { return primaryRole; }
    String secondaryRole() { return secondaryRole; }
    boolean ready() { return ready; }
    boolean simulated() { return simulated; }
}
