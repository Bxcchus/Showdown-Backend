package lol.pinkward.showdown.match;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DuelRatingProjectionBackfillTest {

    @Mock DuelPlayerRatingRepository ratings;
    @Mock MatchOutboxRepository outbox;
    @Mock ObjectMapper objectMapper;

    @Test
    void republishesAnExistingRatingOnlyOnceForItsCurrentVersion() throws Exception {
        var rating = DuelPlayerRating.initial(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "S2026",
                "EUW",
                Instant.parse("2026-08-29T00:00:00Z"));
        rating.apply(
                true,
                Glicko2Calculator.initialRating(),
                Instant.parse("2026-08-29T00:05:00Z"));
        when(ratings.findAll()).thenReturn(List.of(rating));
        when(outbox.existsById(any())).thenReturn(false);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        new DuelRatingProjectionBackfill(ratings, outbox, objectMapper)
                .republishCurrentRatings();

        verify(outbox).save(any(MatchOutboxEvent.class));
    }

    @Test
    void skipsAProjectionVersionAlreadyPresentInTheOutbox() {
        var rating = DuelPlayerRating.initial(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "S2026",
                "EUW",
                Instant.parse("2026-08-29T00:00:00Z"));
        rating.apply(
                true,
                Glicko2Calculator.initialRating(),
                Instant.parse("2026-08-29T00:05:00Z"));
        when(ratings.findAll()).thenReturn(List.of(rating));
        when(outbox.existsById(any())).thenReturn(true);

        new DuelRatingProjectionBackfill(ratings, outbox, objectMapper)
                .republishCurrentRatings();

        verify(outbox, never()).save(any());
    }
}
