package lol.pinkward.showdown.matchmaking;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class QueueService {

    private final QueueEntryRepository entries;
    private final PlayerRatingSnapshotRepository ratings;
    private final DuelRatingSnapshotRepository duelRatings;
    private final Clock clock;

    @Autowired
    QueueService(
            QueueEntryRepository entries,
            PlayerRatingSnapshotRepository ratings,
            DuelRatingSnapshotRepository duelRatings) {
        this(entries, ratings, duelRatings, Clock.systemUTC());
    }

    QueueService(
            QueueEntryRepository entries,
            PlayerRatingSnapshotRepository ratings,
            DuelRatingSnapshotRepository duelRatings,
            Clock clock) {
        this.entries = entries;
        this.ratings = ratings;
        this.duelRatings = duelRatings;
        this.clock = clock;
    }

    @Transactional
    QueueSnapshot join(
            UUID playerId,
            String region,
            String mode,
            String primaryRole,
            String secondaryRole,
            String idempotencyKey) {
        String normalizedRegion = normalizeRegion(region);
        String normalizedMode = normalizeMode(mode);
        String normalizedPrimaryRole = normalizeRole(primaryRole);
        String normalizedSecondaryRole = normalizeRole(secondaryRole);
        if (normalizedPrimaryRole.equals(normalizedSecondaryRole)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "primary and secondary roles must differ");
        }
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        return entries.findByPlayerId(playerId)
                .map(existing -> {
                    if (!existing.idempotencyKey().equals(normalizedKey)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Player already has an active queue operation");
                    }
                    return QueueSnapshot.from(existing);
                })
                .orElseGet(() -> {
                    QueueSkill skill = skillOf(playerId, normalizedMode, normalizedRegion);
                    return QueueSnapshot.from(entries.save(QueueEntry.joinSolo(
                                playerId,
                                normalizedRegion,
                                normalizedMode,
                                normalizedKey,
                                clock.instant(),
                                normalizedPrimaryRole,
                                normalizedSecondaryRole,
                                false,
                                skill.rating(),
                                skill.mean(),
                                skill.deviation())));
                });
    }

    @Transactional(readOnly = true)
    QueueSnapshot status(UUID playerId) {
        return entries.findByPlayerId(playerId)
                .map(QueueSnapshot::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not queued"));
    }

    @Transactional
    void leave(UUID playerId) {
        entries.findByPlayerId(playerId).ifPresent(entry -> {
            if (entry.status() == QueueStatus.RESERVED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation is already in progress");
            }
            entries.delete(entry);
        });
    }

    @Transactional
    List<QueueSnapshot> joinParty(
            UUID partyId,
            String region,
            List<PartyQueueMember> members,
            String idempotencyKey) {
        if (partyId == null || members == null || members.isEmpty() || members.size() > 5
                || members.stream().map(PartyQueueMember::playerId).distinct().count() != members.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A party requires one to five distinct members");
        }
        String normalizedRegion = normalizeRegion(region);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        var existingParty = entries.findByPartyIdOrderByJoinedAtAscIdAsc(partyId);
        if (!existingParty.isEmpty()) {
            if (existingParty.size() != members.size()
                    || existingParty.stream().anyMatch(entry -> !entry.idempotencyKey().equals(normalizedKey))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Party already has an active queue operation");
            }
            return existingParty.stream().map(QueueSnapshot::from).toList();
        }
        if (members.stream().anyMatch(member -> entries.findByPlayerId(member.playerId()).isPresent())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A party member is already queued");
        }

        Instant joinedAt = clock.instant();
        List<QueueSkill> humanSkills = members.stream()
                .filter(member -> !member.simulated())
                .map(member -> skillOf(member.playerId(), "FIVE_V_FIVE", normalizedRegion))
                .toList();
        QueueSkill partySkill = new QueueSkill(
                (int) Math.round(humanSkills.stream().mapToInt(QueueSkill::rating).average()
                        .orElse(PlayerRatingSnapshot.INITIAL_RATING)),
                humanSkills.stream().mapToDouble(QueueSkill::mean).average()
                        .orElse(PlayerRatingSnapshot.INITIAL_SKILL_MEAN),
                humanSkills.stream().mapToDouble(QueueSkill::deviation).average()
                        .orElse(PlayerRatingSnapshot.INITIAL_SKILL_DEVIATION));
        var queued = members.stream().map(member -> {
            QueueSkill skill = member.simulated()
                    ? partySkill
                    : skillOf(member.playerId(), "FIVE_V_FIVE", normalizedRegion);
            return QueueEntry.join(
                    member.playerId(),
                    partyId,
                    normalizedRegion,
                    normalizedKey,
                    joinedAt,
                    normalizeRole(member.primaryRole()),
                    normalizeRole(member.secondaryRole()),
                    member.simulated(),
                    skill.rating(),
                    skill.mean(),
                    skill.deviation());
        }).toList();
        if (queued.stream().anyMatch(entry -> entry.primaryRole().equals(entry.secondaryRole()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "primary and secondary roles must differ");
        }
        return entries.saveAll(queued).stream().map(QueueSnapshot::from).toList();
    }

    @Transactional
    void leaveParty(UUID partyId) {
        var partyEntries = entries.findByPartyIdOrderByJoinedAtAscIdAsc(partyId);
        if (partyEntries.stream().anyMatch(entry -> entry.status() == QueueStatus.RESERVED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation is already in progress");
        }
        entries.deleteAll(partyEntries);
    }

    private static String normalizeRegion(String region) {
        if (region == null || region.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "region is required");
        }
        String normalized = region.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("EUW", "EUNE", "NA").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported region");
        }
        return normalized;
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
        return key.trim();
    }

    private static String normalizeMode(String mode) {
        String normalized = mode == null || mode.isBlank()
                ? "FIVE_V_FIVE" : mode.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("FIVE_V_FIVE", "ONE_V_ONE").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported mode");
        }
        return normalized;
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role is required");
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("TOP", "JUNGLE", "MID", "BOT", "SUPPORT").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported role");
        }
        return normalized;
    }

    private QueueSkill skillOf(UUID playerId, String mode, String region) {
        if ("ONE_V_ONE".equals(mode)) {
            return duelRatings.findById(playerId)
                    .filter(rating -> rating.region().equals(region))
                    .map(rating -> new QueueSkill(
                            rating.mmr(), rating.queueMean(), rating.queueDeviation()))
                    .orElseGet(() -> new QueueSkill(
                            DuelRatingSnapshot.INITIAL_MMR,
                            PlayerRatingSnapshot.INITIAL_SKILL_MEAN,
                            DuelRatingSnapshot.INITIAL_RATING_DEVIATION / 40.0));
        }
        return ratings.findById(playerId)
                .map(rating -> new QueueSkill(
                        rating.rating(), rating.skillMean(), rating.skillDeviation()))
                .orElseGet(() -> new QueueSkill(
                        PlayerRatingSnapshot.INITIAL_RATING,
                        PlayerRatingSnapshot.INITIAL_SKILL_MEAN,
                        PlayerRatingSnapshot.INITIAL_SKILL_DEVIATION));
    }

    record PartyQueueMember(UUID playerId, String primaryRole, String secondaryRole, boolean simulated) {}
    private record QueueSkill(int rating, double mean, double deviation) {}
}
