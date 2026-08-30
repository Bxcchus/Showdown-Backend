package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrueSkillCalculatorTest {

    @Test
    void updatesBothFivePlayerTeamsAndReducesUncertainty() {
        List<TrueSkillCalculator.Competitor> roster = IntStream.range(0, 10)
                .mapToObj(index -> new TrueSkillCalculator.Competitor(
                        UUID.randomUUID(),
                        index < 5 ? TeamSide.BLUE : TeamSide.RED,
                        TrueSkillCalculator.initialSkill()))
                .toList();

        var result = TrueSkillCalculator.rate(roster, TeamSide.BLUE);

        assertThat(result).hasSize(10);
        roster.subList(0, 5).forEach(player -> {
            assertThat(result.get(player.playerId()).mean()).isGreaterThan(TrueSkillCalculator.INITIAL_MEAN);
            assertThat(result.get(player.playerId()).deviation())
                    .isLessThan(TrueSkillCalculator.INITIAL_DEVIATION);
        });
        roster.subList(5, 10).forEach(player ->
                assertThat(result.get(player.playerId()).mean()).isLessThan(TrueSkillCalculator.INITIAL_MEAN));
    }

    @Test
    void conservativeMmrStartsAtTheExistingLadderBaseline() {
        assertThat(TrueSkillCalculator.displayMmr(TrueSkillCalculator.initialSkill())).isEqualTo(1200);
    }

    @Test
    void seasonRolloverKeepsHalfTheMeanAdvantageAndRestoresUncertainty() {
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        PlayerRating rating = PlayerRating.initial(UUID.randomUUID(), "S2026", "EUW", now.minusSeconds(1));
        rating.apply(true, new TrueSkillCalculator.Skill(31.0, 5.0), now.minusSeconds(1));
        double previousDeviation = rating.skillDeviation();

        rating.startSeason(RatingSeason.of(
                "S2027", now, now.plusSeconds(31_536_000), 5, 0.5), "EUW", now);

        assertThat(rating.seasonCode()).isEqualTo("S2027");
        assertThat(rating.skillMean()).isEqualTo(28.0);
        assertThat(rating.skillDeviation()).isGreaterThan(previousDeviation);
        assertThat(rating.games()).isZero();
        assertThat(rating.progression()).isZero();
    }
}
