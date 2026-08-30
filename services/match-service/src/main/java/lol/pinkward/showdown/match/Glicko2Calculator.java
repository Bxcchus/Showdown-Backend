package lol.pinkward.showdown.match;

import java.util.List;

/** Standard Glicko-2 update. One completed ONE_V_ONE match is one rating period. */
final class Glicko2Calculator {

    static final double INITIAL_RATING = 1500.0;
    static final double INITIAL_DEVIATION = 350.0;
    static final double INITIAL_VOLATILITY = 0.06;
    static final double TAU = 0.5;
    static final double SCALE = 173.7178;
    private static final double EPSILON = 0.000001;
    private static final double MIN_DEVIATION = 30.0;
    private static final int MAX_ITERATIONS = 100;

    private Glicko2Calculator() {}

    static DuelResult rateDuel(Rating first, Rating second, boolean firstWon) {
        Rating firstUpdated = rate(first, List.of(new Result(second, firstWon ? 1.0 : 0.0)));
        Rating secondUpdated = rate(second, List.of(new Result(first, firstWon ? 0.0 : 1.0)));
        return new DuelResult(firstUpdated, secondUpdated);
    }

    static Rating rate(Rating player, List<Result> results) {
        if (results == null || results.isEmpty()) {
            double phi = player.deviation() / SCALE;
            double expanded = Math.sqrt(phi * phi + player.volatility() * player.volatility()) * SCALE;
            return new Rating(player.rating(), Math.min(INITIAL_DEVIATION, expanded), player.volatility());
        }

        double mu = (player.rating() - INITIAL_RATING) / SCALE;
        double phi = player.deviation() / SCALE;
        double varianceInverse = 0.0;
        double improvement = 0.0;
        for (Result result : results) {
            double opponentMu = (result.opponent().rating() - INITIAL_RATING) / SCALE;
            double opponentPhi = result.opponent().deviation() / SCALE;
            double impact = impact(opponentPhi);
            double expected = expected(mu, opponentMu, opponentPhi);
            varianceInverse += impact * impact * expected * (1.0 - expected);
            improvement += impact * (result.score() - expected);
        }

        double variance = 1.0 / varianceInverse;
        double delta = variance * improvement;
        double volatility = updatedVolatility(phi, player.volatility(), variance, delta);
        double preRatingDeviation = Math.sqrt(phi * phi + volatility * volatility);
        double updatedPhi = 1.0 / Math.sqrt(1.0 / (preRatingDeviation * preRatingDeviation) + 1.0 / variance);
        double updatedMu = mu + updatedPhi * updatedPhi * improvement;

        return new Rating(
                updatedMu * SCALE + INITIAL_RATING,
                Math.max(MIN_DEVIATION, Math.min(INITIAL_DEVIATION, updatedPhi * SCALE)),
                volatility);
    }

    private static double impact(double opponentPhi) {
        return 1.0 / Math.sqrt(1.0 + 3.0 * opponentPhi * opponentPhi / (Math.PI * Math.PI));
    }

    private static double expected(double mu, double opponentMu, double opponentPhi) {
        return 1.0 / (1.0 + Math.exp(-impact(opponentPhi) * (mu - opponentMu)));
    }

    private static double updatedVolatility(double phi, double volatility, double variance, double delta) {
        double alpha = Math.log(volatility * volatility);
        double a = alpha;
        double b;
        if (delta * delta > phi * phi + variance) {
            b = Math.log(delta * delta - phi * phi - variance);
        } else {
            int k = 1;
            do {
                b = alpha - k * TAU;
                k++;
                if (k > MAX_ITERATIONS) {
                    throw new IllegalStateException("Glicko-2 volatility bounds did not converge");
                }
            } while (volatilityFunction(b, delta, phi, variance, alpha) < 0.0);
        }

        double valueA = volatilityFunction(a, delta, phi, variance, alpha);
        double valueB = volatilityFunction(b, delta, phi, variance, alpha);
        int iterations = 0;
        while (Math.abs(b - a) > EPSILON) {
            double c = a + (a - b) * valueA / (valueB - valueA);
            double valueC = volatilityFunction(c, delta, phi, variance, alpha);
            if (valueC * valueB <= 0.0) {
                a = b;
                valueA = valueB;
            } else {
                valueA /= 2.0;
            }
            b = c;
            valueB = valueC;
            if (++iterations > MAX_ITERATIONS) {
                throw new IllegalStateException("Glicko-2 volatility update did not converge");
            }
        }
        return Math.exp(a / 2.0);
    }

    private static double volatilityFunction(
            double value, double delta, double phi, double variance, double alpha) {
        double exponential = Math.exp(value);
        double denominator = phi * phi + variance + exponential;
        return exponential * (delta * delta - phi * phi - variance - exponential)
                / (2.0 * denominator * denominator)
                - (value - alpha) / (TAU * TAU);
    }

    static Rating initialRating() {
        return new Rating(INITIAL_RATING, INITIAL_DEVIATION, INITIAL_VOLATILITY);
    }

    record Rating(double rating, double deviation, double volatility) {
        Rating {
            if (!Double.isFinite(rating) || !Double.isFinite(deviation) || !Double.isFinite(volatility)
                    || deviation <= 0.0 || volatility <= 0.0) {
                throw new IllegalArgumentException("A Glicko-2 rating requires finite positive RD and volatility");
            }
        }

        int displayed() {
            return Math.max(0, (int) Math.round(rating));
        }
    }

    record Result(Rating opponent, double score) {
        Result {
            if (opponent == null || (score != 0.0 && score != 0.5 && score != 1.0)) {
                throw new IllegalArgumentException("A Glicko-2 result requires an opponent and a valid score");
            }
        }
    }

    record DuelResult(Rating first, Rating second) {}
}
