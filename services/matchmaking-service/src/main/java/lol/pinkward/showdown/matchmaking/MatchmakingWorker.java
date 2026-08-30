package lol.pinkward.showdown.matchmaking;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class MatchmakingWorker {

    private final MatchReservationService reservations;
    private final LocalBotFillService bots;
    private final int maxMatchesPerTick;

    MatchmakingWorker(
            MatchReservationService reservations,
            LocalBotFillService bots,
            @Value("${pinkward.matchmaking.max-matches-per-tick:100}") int maxMatchesPerTick) {
        this.reservations = reservations;
        this.bots = bots;
        this.maxMatchesPerTick = maxMatchesPerTick;
    }

    @Scheduled(fixedDelayString = "${pinkward.matchmaking.tick:1s}")
    void tick() {
        for (String region : List.of("EUW", "EUNE", "NA")) {
            for (String mode : List.of("FIVE_V_FIVE", "ONE_V_ONE")) {
                bots.fillIfEligible(region, mode);
                for (int match = 0; match < maxMatchesPerTick; match++) {
                    if (!reservations.reserveOne(region, mode)) break;
                }
            }
        }
    }
}
