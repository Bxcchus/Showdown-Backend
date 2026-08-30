package lol.pinkward.showdown.player;

import java.time.Instant;
import java.util.UUID;

record PartyInvitationView(
        UUID invitationId,
        UUID partyId,
        String inviterDisplayName,
        String region,
        Instant expiresAt) {}
