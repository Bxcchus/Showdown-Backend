package lol.pinkward.showdown.matchmaking;

import java.time.Instant;
import java.util.UUID;
import lol.pinkward.showdown.contracts.MatchConfirmedPayload;

record MatchConfirmedEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        UUID causationId,
        MatchConfirmedPayload payload) {}
