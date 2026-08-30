package lol.pinkward.showdown.player;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record PartySnapshot(
        UUID partyId,
        UUID leaderId,
        String region,
        boolean viewerIsLeader,
        boolean allReady,
        int capacity,
        List<Member> members,
        List<Invitation> invitations) {

    record Member(
            UUID playerId,
            String displayName,
            String primaryRole,
            String secondaryRole,
            boolean ready,
            boolean online,
            boolean simulated) {}

    record Invitation(
            UUID invitationId,
            UUID inviteeId,
            String displayName,
            String status,
            Instant expiresAt) {}
}
