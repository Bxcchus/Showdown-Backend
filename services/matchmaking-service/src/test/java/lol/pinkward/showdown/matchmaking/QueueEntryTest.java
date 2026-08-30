package lol.pinkward.showdown.matchmaking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QueueEntryTest {

    @Test
    void enforcesTheQueuedToReservedTransition() {
        QueueEntry entry = QueueEntry.join(
                UUID.randomUUID(), "EUW", "operation-1", Instant.parse("2026-08-26T17:00:00Z"));
        UUID reservationId = UUID.randomUUID();

        entry.reserve(reservationId);

        assertThat(entry.status()).isEqualTo(QueueStatus.RESERVED);
        assertThat(entry.reservationId()).isEqualTo(reservationId);
        assertThatThrownBy(() -> entry.reserve(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);

        entry.requeue(Instant.parse("2026-08-26T17:01:00Z"));

        assertThat(entry.status()).isEqualTo(QueueStatus.QUEUED);
        assertThat(entry.reservationId()).isNull();
    }
}
