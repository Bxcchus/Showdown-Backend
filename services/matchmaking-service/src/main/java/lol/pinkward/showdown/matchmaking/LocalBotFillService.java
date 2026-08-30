package lol.pinkward.showdown.matchmaking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LocalBotFillService {

    private final QueueEntryRepository entries;
    private final boolean enabled;
    private final Duration fillAfter;
    private final Clock clock;

    @Autowired
    LocalBotFillService(
            QueueEntryRepository entries,
            @Value("${pinkward.matchmaking.local-bots.enabled:false}") boolean enabled,
            @Value("${pinkward.matchmaking.local-bots.fill-after:5s}") Duration fillAfter) {
        this(entries, enabled, fillAfter, Clock.systemUTC());
    }

    LocalBotFillService(QueueEntryRepository entries, boolean enabled, Duration fillAfter, Clock clock) {
        this.entries = entries;
        this.enabled = enabled;
        this.fillAfter = fillAfter;
        this.clock = clock;
    }

    @Transactional
    public int fillIfEligible(String region) {
        return fillIfEligible(region, "FIVE_V_FIVE");
    }

    @Transactional
    public int fillIfEligible(String region, String mode) {
        if (!enabled || !"EUW".equals(region)) return 0;

        int targetPlayers = "ONE_V_ONE".equals(mode) ? 2 : 10;
        List<QueueEntry> queuedEntries = entries.findQueuedForBotFill(region, mode);
        long queued = queuedEntries.size();
        if (queued < 1 || queued >= targetPlayers) return 0;

        Instant now = clock.instant();
        Instant oldestHuman = queuedEntries.stream()
                .filter(entry -> !entry.isLocalBot())
                .map(QueueEntry::joinedAt)
                .min(Instant::compareTo)
                .orElse(null);
        if (oldestHuman == null || oldestHuman.isAfter(now.minus(fillAfter))) return 0;

        int botsNeeded = targetPlayers - Math.toIntExact(queued);
        int referenceMmr = (int) Math.round(queuedEntries.stream()
                .filter(entry -> !entry.isLocalBot())
                .mapToInt(QueueEntry::mmr)
                .average()
                .orElse(PlayerRatingSnapshot.INITIAL_RATING));
        double referenceMean = queuedEntries.stream()
                .filter(entry -> !entry.isLocalBot())
                .mapToDouble(QueueEntry::skillMean)
                .average()
                .orElse(PlayerRatingSnapshot.INITIAL_SKILL_MEAN);
        double referenceDeviation = queuedEntries.stream()
                .filter(entry -> !entry.isLocalBot())
                .mapToDouble(QueueEntry::skillDeviation)
                .average()
                .orElse(PlayerRatingSnapshot.INITIAL_SKILL_DEVIATION);
        String batch = UUID.randomUUID().toString();
        var roles = "ONE_V_ONE".equals(mode)
                ? List.of("MID") : List.of("TOP", "JUNGLE", "MID", "BOT", "SUPPORT");
        var counts = new LinkedHashMap<String, Integer>();
        roles.forEach(role -> counts.put(role, 0));
        queuedEntries.forEach(entry -> counts.computeIfPresent(entry.primaryRole(), (role, count) -> count + 1));
        var botPrimaries = new ArrayList<String>(botsNeeded);
        for (String role : roles) {
            int desiredRoleCount = "ONE_V_ONE".equals(mode) ? 2 : 2;
            for (int missing = Math.max(0, desiredRoleCount - counts.get(role)); missing > 0 && botPrimaries.size() < botsNeeded; missing--) {
                botPrimaries.add(role);
            }
        }
        while (botPrimaries.size() < botsNeeded) botPrimaries.add(roles.get(botPrimaries.size() % roles.size()));

        var bots = new ArrayList<QueueEntry>(botsNeeded);
        for (int index = 0; index < botsNeeded; index++) {
            String primary = botPrimaries.get(index);
            String secondary = "MID".equals(primary) ? "JUNGLE" : roles.get((roles.indexOf(primary) + 1) % roles.size());
            bots.add(QueueEntry.joinSolo(
                    UUID.randomUUID(),
                    region,
                    mode,
                    QueueEntry.LOCAL_BOT_PREFIX + "fill:" + batch + ":" + index,
                    now.plusNanos(index),
                    primary,
                    secondary,
                    true,
                    referenceMmr,
                    referenceMean,
                    referenceDeviation));
        }
        entries.saveAll(bots);
        return bots.size();
    }
}
