package lol.pinkward.showdown.match;

import java.time.Instant;
import java.util.UUID;
import lol.pinkward.showdown.contracts.MatchFoundPayload;

record MatchFoundEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        UUID causationId,
        MatchFoundPayload payload) {}
