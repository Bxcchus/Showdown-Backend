package lol.pinkward.showdown.matchmaking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LocalBotFillServiceTest {

    @Test
    void fillsEuwWithNineBotsAfterARealPlayerWaitsFiveSeconds() {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        Instant now = Instant.parse("2026-08-26T20:00:00Z");
        QueueEntry human = QueueEntry.join(
                UUID.randomUUID(), "EUW", "human", now.minusSeconds(5), "JUNGLE", "MID");
        when(entries.findQueuedForBotFill("EUW", "FIVE_V_FIVE")).thenReturn(List.of(human));
        var service = new LocalBotFillService(
                entries, true, Duration.ofSeconds(5), Clock.fixed(now, ZoneOffset.UTC));

        int created = service.fillIfEligible("EUW");

        assertThat(created).isEqualTo(9);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<QueueEntry>> bots = ArgumentCaptor.forClass(java.util.List.class);
        verify(entries).saveAll(bots.capture());
        assertThat(bots.getValue()).hasSize(9).allMatch(QueueEntry::isLocalBot);
        var primaryRoles = new java.util.ArrayList<String>();
        primaryRoles.add(human.primaryRole());
        bots.getValue().forEach(bot -> primaryRoles.add(bot.primaryRole()));
        assertThat(primaryRoles).filteredOn("JUNGLE"::equals).hasSize(2);
        assertThat(primaryRoles).filteredOn("TOP"::equals).hasSize(2);
        assertThat(primaryRoles).filteredOn("MID"::equals).hasSize(2);
        assertThat(primaryRoles).filteredOn("BOT"::equals).hasSize(2);
        assertThat(primaryRoles).filteredOn("SUPPORT"::equals).hasSize(2);
    }

    @Test
    void fillsOneVOneWithOneUnratedBotAfterFiveSeconds() {
        QueueEntryRepository entries = mock(QueueEntryRepository.class);
        Instant now = Instant.parse("2026-08-26T20:00:00Z");
        QueueEntry human = QueueEntry.joinSolo(
                UUID.randomUUID(), "EUW", "ONE_V_ONE", "human-duel", now.minusSeconds(5),
                "MID", "JUNGLE", false, 1500, 25.0, 8.333);
        when(entries.findQueuedForBotFill("EUW", "ONE_V_ONE")).thenReturn(List.of(human));
        var service = new LocalBotFillService(
                entries, true, Duration.ofSeconds(5), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.fillIfEligible("EUW", "ONE_V_ONE")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<QueueEntry>> bots = ArgumentCaptor.forClass(java.util.List.class);
        verify(entries).saveAll(bots.capture());
        assertThat(bots.getValue()).singleElement().satisfies(bot -> {
            assertThat(bot.mode()).isEqualTo("ONE_V_ONE");
            assertThat(bot.primaryRole()).isEqualTo("MID");
            assertThat(bot.isLocalBot()).isTrue();
        });
    }
}
