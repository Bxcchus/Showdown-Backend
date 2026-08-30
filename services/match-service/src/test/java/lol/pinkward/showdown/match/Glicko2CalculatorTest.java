package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class Glicko2CalculatorTest {

    @Test
    void reproducesThePublishedGlicko2Example() {
        var player = new Glicko2Calculator.Rating(1500, 200, 0.06);
        var updated = Glicko2Calculator.rate(player, List.of(
                new Glicko2Calculator.Result(new Glicko2Calculator.Rating(1400, 30, 0.06), 1.0),
                new Glicko2Calculator.Result(new Glicko2Calculator.Rating(1550, 100, 0.06), 0.0),
                new Glicko2Calculator.Result(new Glicko2Calculator.Rating(1700, 300, 0.06), 0.0)));

        assertThat(updated.rating()).isCloseTo(1464.06, within(0.01));
        assertThat(updated.deviation()).isCloseTo(151.52, within(0.01));
        assertThat(updated.volatility()).isCloseTo(0.059996, within(0.000001));
    }

    @Test
    void ratesAnEqualOneVersusOneSymmetrically() {
        var initial = Glicko2Calculator.initialRating();
        var result = Glicko2Calculator.rateDuel(initial, initial, true);

        assertThat(result.first().rating()).isGreaterThan(1500);
        assertThat(result.second().rating()).isLessThan(1500);
        assertThat(result.first().rating() - 1500).isCloseTo(
                1500 - result.second().rating(), within(0.000001));
        assertThat(result.first().deviation()).isLessThan(350);
        assertThat(result.second().deviation()).isEqualTo(result.first().deviation());
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
