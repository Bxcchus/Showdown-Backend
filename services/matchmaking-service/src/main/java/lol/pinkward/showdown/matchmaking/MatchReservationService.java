package lol.pinkward.showdown.matchmaking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import lol.pinkward.showdown.contracts.EventEnvelope;
import lol.pinkward.showdown.contracts.MatchFoundPayload;
import lol.pinkward.showdown.contracts.PlayerRolePreference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
class MatchReservationService {

    static final String MATCH_FOUND = "MATCH_FOUND";
    static final String ROUTING_KEY = "pinkward.matchmaking.match-found.v1";
    private static final List<String> ROLE_ORDER = List.of("TOP", "JUNGLE", "MID", "BOT", "SUPPORT");

    private final QueueEntryRepository entries;
    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Duration roleWidenAfter;
    private final int mmrInitialRange;
    private final int mmrWidenStep;
    private final Duration mmrWidenEvery;
    private final int mmrMaxRange;
    private final Clock clock;

    @Autowired
    MatchReservationService(
            QueueEntryRepository entries,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper,
            @Value("${pinkward.matchmaking.role-widen-after:30s}") Duration roleWidenAfter,
            @Value("${pinkward.matchmaking.mmr.initial-range:100}") int mmrInitialRange,
            @Value("${pinkward.matchmaking.mmr.widen-step:50}") int mmrWidenStep,
            @Value("${pinkward.matchmaking.mmr.widen-every:15s}") Duration mmrWidenEvery,
            @Value("${pinkward.matchmaking.mmr.max-range:600}") int mmrMaxRange) {
        this(entries, outbox, objectMapper, roleWidenAfter,
                mmrInitialRange, mmrWidenStep, mmrWidenEvery, mmrMaxRange, Clock.systemUTC());
    }

    MatchReservationService(
            QueueEntryRepository entries,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper,
            Duration roleWidenAfter,
            Clock clock) {
        this(entries, outbox, objectMapper, roleWidenAfter, 100, 50, Duration.ofSeconds(15), 600, clock);
    }

    MatchReservationService(
            QueueEntryRepository entries,
            OutboxEventRepository outbox,
            ObjectMapper objectMapper,
            Duration roleWidenAfter,
            int mmrInitialRange,
            int mmrWidenStep,
            Duration mmrWidenEvery,
            int mmrMaxRange,
            Clock clock) {
        this.entries = entries;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.roleWidenAfter = roleWidenAfter;
        this.mmrInitialRange = Math.max(0, mmrInitialRange);
        this.mmrWidenStep = Math.max(0, mmrWidenStep);
        this.mmrWidenEvery = mmrWidenEvery.isZero() || mmrWidenEvery.isNegative()
                ? Duration.ofSeconds(15) : mmrWidenEvery;
        this.mmrMaxRange = Math.max(this.mmrInitialRange, mmrMaxRange);
        this.clock = clock;
    }

    @Transactional
    public boolean reserveOne(String region) {
        return reserveOne(region, "FIVE_V_FIVE");
    }

    @Transactional
    public boolean reserveOne(String region, String mode) {
        int targetPlayers = "ONE_V_ONE".equals(mode) ? 2 : 10;
        List<QueueEntry> candidates = entries.lockCandidates(region, mode);
        if (candidates.size() < targetPlayers) return false;
        List<QueueEntry> mmrCandidates = withinMmrWindow(candidates);
        if (mmrCandidates.size() < targetPlayers) return false;
        List<QueueEntry> selected = "ONE_V_ONE".equals(mode)
                ? List.copyOf(mmrCandidates.subList(0, 2))
                : selectPartySafe(mmrCandidates, true);
        if (selected.isEmpty()) {
            Instant wideningDeadline = candidates.getFirst().joinedAt().plus(roleWidenAfter);
            if (clock.instant().isBefore(wideningDeadline)) return false;
            selected = selectPartySafe(mmrCandidates, false);
            if (selected.isEmpty()) return false;
        }
        UUID reservationId = UUID.randomUUID();
        selected.forEach(entry -> entry.reserve(reservationId));
        UUID eventId = UUID.randomUUID();
        EventEnvelope<MatchFoundPayload> envelope = new EventEnvelope<>(
                eventId,
                MATCH_FOUND,
                1,
                clock.instant(),
                "matchmaking-service",
                reservationId,
                null,
                new MatchFoundPayload(
                        reservationId,
                        region,
                        mode,
                        selected.stream().map(QueueEntry::playerId).toList(),
                        selected.stream()
                                .filter(QueueEntry::isLocalBot)
                                .map(QueueEntry::playerId)
                                .toList(),
                        selected.stream()
                                .map(entry -> new PlayerRolePreference(
                                        entry.playerId(), entry.primaryRole(), entry.secondaryRole()))
                                .toList()));
        outbox.save(OutboxEvent.pending(eventId, MATCH_FOUND, ROUTING_KEY, serialize(envelope), clock.instant()));
        return true;
    }

    private List<QueueEntry> withinMmrWindow(List<QueueEntry> candidates) {
        var grouped = groupUnits(candidates);
        QueueUnit anchor = grouped.getFirst();
        long wideningSteps = Math.max(0, Duration.between(anchor.oldestJoinedAt(), clock.instant()).toMillis())
                / Math.max(1, mmrWidenEvery.toMillis());
        int window = (int) Math.min(mmrMaxRange,
                (long) mmrInitialRange + wideningSteps * mmrWidenStep);
        double anchorMean = anchor.averageSkillMean();
        return grouped.stream()
                .filter(unit -> Math.abs(unit.averageSkillMean() - anchorMean) * 40.0 <= window)
                .flatMap(unit -> unit.entries().stream())
                .toList();
    }

    private List<QueueEntry> selectPartySafe(List<QueueEntry> candidates, boolean requirePrimaryCoverage) {
        if (requirePrimaryCoverage && ROLE_ORDER.stream().anyMatch(role ->
                candidates.stream().filter(entry -> role.equals(entry.primaryRole())).count() < 2)) {
            return List.of();
        }

        List<QueueUnit> units = groupUnits(candidates);
        return chooseUnits(units, 0, new ArrayList<>(), 0, requirePrimaryCoverage);
    }

    private List<QueueUnit> groupUnits(List<QueueEntry> candidates) {
        var grouped = new LinkedHashMap<UUID, List<QueueEntry>>();
        for (QueueEntry entry : candidates) {
            UUID unitId = entry.partyId() == null ? entry.id() : entry.partyId();
            grouped.computeIfAbsent(unitId, ignored -> new ArrayList<>()).add(entry);
        }
        return grouped.values().stream().map(QueueUnit::new).toList();
    }

    private List<QueueEntry> chooseUnits(
            List<QueueUnit> units,
            int index,
            List<QueueUnit> selected,
            int selectedPlayers,
            boolean requirePrimaryCoverage) {
        if (selectedPlayers == 10) {
            List<QueueEntry> roster = selected.stream().flatMap(unit -> unit.entries().stream()).toList();
            if (requirePrimaryCoverage && ROLE_ORDER.stream().anyMatch(role ->
                    roster.stream().filter(entry -> role.equals(entry.primaryRole())).count() < 2)) {
                return List.of();
            }
            return arrangeTeams(selected);
        }
        if (index >= units.size() || selectedPlayers > 10) return List.of();

        QueueUnit unit = units.get(index);
        if (selectedPlayers + unit.entries().size() <= 10) {
            selected.add(unit);
            List<QueueEntry> included = chooseUnits(
                    units, index + 1, selected, selectedPlayers + unit.entries().size(), requirePrimaryCoverage);
            selected.removeLast();
            if (!included.isEmpty()) return included;
        }
        return chooseUnits(units, index + 1, selected, selectedPlayers, requirePrimaryCoverage);
    }

    private List<QueueEntry> arrangeTeams(List<QueueUnit> units) {
        BestTeam best = new BestTeam();
        chooseBalancedBlueTeam(units, 0, new ArrayList<>(), 0, 0.0, totalSkillMean(units), best);
        if (best.units.isEmpty()) return List.of();
        var blueSet = new java.util.HashSet<>(best.units);
        var ordered = new ArrayList<QueueEntry>(10);
        best.units.forEach(unit -> ordered.addAll(unit.entries()));
        units.stream().filter(unit -> !blueSet.contains(unit)).forEach(unit -> ordered.addAll(unit.entries()));
        return List.copyOf(ordered);
    }

    private void chooseBalancedBlueTeam(
            List<QueueUnit> units,
            int index,
            List<QueueUnit> selected,
            int size,
            double skillMean,
            double totalSkillMean,
            BestTeam best) {
        if (size == 5) {
            double difference = Math.abs(totalSkillMean - 2.0 * skillMean);
            if (difference < best.difference) {
                best.difference = difference;
                best.units = List.copyOf(selected);
            }
            return;
        }
        if (index >= units.size() || size > 5) return;
        QueueUnit unit = units.get(index);
        if (size + unit.entries().size() <= 5) {
            selected.add(unit);
            chooseBalancedBlueTeam(
                    units, index + 1, selected, size + unit.entries().size(),
                    skillMean + unit.totalSkillMean(), totalSkillMean, best);
            selected.removeLast();
        }
        chooseBalancedBlueTeam(units, index + 1, selected, size, skillMean, totalSkillMean, best);
    }

    private record QueueUnit(List<QueueEntry> entries) {
        QueueUnit { entries = List.copyOf(entries); }
        double totalSkillMean() { return entries.stream().mapToDouble(QueueEntry::skillMean).sum(); }
        double averageSkillMean() {
            return entries.stream().mapToDouble(QueueEntry::skillMean)
                    .average().orElse(PlayerRatingSnapshot.INITIAL_SKILL_MEAN);
        }
        Instant oldestJoinedAt() { return entries.stream().map(QueueEntry::joinedAt).min(Instant::compareTo).orElseThrow(); }
    }

    private static double totalSkillMean(List<QueueUnit> units) {
        return units.stream().mapToDouble(QueueUnit::totalSkillMean).sum();
    }

    private static final class BestTeam {
        private double difference = Double.POSITIVE_INFINITY;
        private List<QueueUnit> units = List.of();
    }

    private String serialize(EventEnvelope<MatchFoundPayload> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize MATCH_FOUND", exception);
        }
    }
}
