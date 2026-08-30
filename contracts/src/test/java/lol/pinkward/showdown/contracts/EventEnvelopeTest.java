package lol.pinkward.showdown.contracts;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void rejectsAMatchWithFewerThanTenDistinctPlayers() {
        UUID player = UUID.randomUUID();
        assertThatThrownBy(() -> new MatchFoundPayload(
                        UUID.randomUUID(), "EUW", "FIVE_V_FIVE",
                        List.of(player, player), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnEnvelopeWithoutCorrelation() {
        assertThatThrownBy(() -> new EventEnvelope<>(
                        UUID.randomUUID(), "MATCH_FOUND", 1, Instant.now(),
                        "matchmaking-service", null, null, "payload"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTwoDistinctHumanPlayersForAOneVersusOneMatch() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThatCode(() -> new MatchFoundPayload(
                UUID.randomUUID(), "EUW", "ONE_V_ONE", List.of(first, second), List.of(),
                List.of(
                        new PlayerRolePreference(first, "MID", "TOP"),
                        new PlayerRolePreference(second, "MID", "TOP"))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOneBotInALocalOneVersusOneTestMatch() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThatCode(() -> new MatchFoundPayload(
                UUID.randomUUID(), "EUW", "ONE_V_ONE", List.of(first, second), List.of(second),
                List.of(
                        new PlayerRolePreference(first, "MID", "TOP"),
                        new PlayerRolePreference(second, "MID", "TOP"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnAllBotOneVersusOneMatch() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThatThrownBy(() -> new MatchFoundPayload(
                UUID.randomUUID(), "EUW", "ONE_V_ONE", List.of(first, second), List.of(first, second),
                List.of(
                        new PlayerRolePreference(first, "MID", "TOP"),
                        new PlayerRolePreference(second, "MID", "TOP"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAConfirmedOneVersusOneRoster() {
        assertThatCode(() -> new MatchConfirmedPayload(
                UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID())))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnInvalidConfirmedRosterSize() {
        assertThatThrownBy(() -> new MatchConfirmedPayload(
                UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
