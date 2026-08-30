package lol.pinkward.showdown.matchmaking;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
class MatchmakingWorkerTest {

    @Test
    void delegatesEveryRegionToTheTransactionalReservationService() {
        MatchReservationService reservations = mock(MatchReservationService.class);
        LocalBotFillService bots = mock(LocalBotFillService.class);
        when(reservations.reserveOne("EUW", "FIVE_V_FIVE")).thenReturn(true, true, false);
        MatchmakingWorker worker = new MatchmakingWorker(reservations, bots, 100);

        worker.tick();

        for (String region : java.util.List.of("EUW", "EUNE", "NA")) {
            verify(bots).fillIfEligible(region, "FIVE_V_FIVE");
            verify(bots).fillIfEligible(region, "ONE_V_ONE");
            verify(reservations).reserveOne(region, "ONE_V_ONE");
        }
        verify(reservations, times(3)).reserveOne("EUW", "FIVE_V_FIVE");
        verify(reservations).reserveOne("EUNE", "FIVE_V_FIVE");
        verify(reservations).reserveOne("NA", "FIVE_V_FIVE");
    }
}
