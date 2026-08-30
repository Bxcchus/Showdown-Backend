package lol.pinkward.showdown.match;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Two-team, no-draw TrueSkill update used by the FIVE_V_FIVE queue. */
final class TrueSkillCalculator {

    static final double INITIAL_MEAN = 25.0;
    static final double INITIAL_DEVIATION = INITIAL_MEAN / 3.0;
    static final double BETA = INITIAL_MEAN / 6.0;
    static final double DYNAMIC_FACTOR = INITIAL_MEAN / 300.0;
    static final int INITIAL_MMR = 1200;
    private static final double MMR_PER_EXPOSURE_POINT = 40.0;
    private static final double INITIAL_EXPOSURE = INITIAL_MEAN - 3.0 * INITIAL_DEVIATION;

    private TrueSkillCalculator() {}

    static Map<UUID, Skill> rate(List<Competitor> competitors, TeamSide winner) {
        if (competitors == null || competitors.size() != 10
                || competitors.stream().filter(player -> player.team() == TeamSide.BLUE).count() != 5
                || competitors.stream().filter(player -> player.team() == TeamSide.RED).count() != 5) {
            throw new IllegalArgumentException("TrueSkill FIVE_V_FIVE requires two teams of five");
        }

        double winnerMean = competitors.stream()
                .filter(player -> player.team() == winner)
                .mapToDouble(player -> player.skill().mean())
                .sum();
        double loserMean = competitors.stream()
                .filter(player -> player.team() != winner)
                .mapToDouble(player -> player.skill().mean())
                .sum();
        double totalVariance = competitors.stream()
                .mapToDouble(player -> priorVariance(player.skill()))
                .sum();
        double c = Math.sqrt(totalVariance + competitors.size() * BETA * BETA);
        double t = (winnerMean - loserMean) / c;
        double v = normalPdf(t) / Math.max(1.0e-12, normalCdf(t));
        double w = v * (v + t);

        Map<UUID, Skill> updated = new LinkedHashMap<>();
        for (Competitor competitor : competitors) {
            double variance = priorVariance(competitor.skill());
            double direction = competitor.team() == winner ? 1.0 : -1.0;
            double mean = competitor.skill().mean() + direction * variance / c * v;
            double deviationSquared = variance * (1.0 - variance / (c * c) * w);
            updated.put(competitor.playerId(), new Skill(mean, Math.sqrt(Math.max(1.0e-9, deviationSquared))));
        }
        return Map.copyOf(updated);
    }

    static int displayMmr(Skill skill) {
        double exposure = skill.mean() - 3.0 * skill.deviation();
        return Math.max(0, (int) Math.round(
                INITIAL_MMR + MMR_PER_EXPOSURE_POINT * (exposure - INITIAL_EXPOSURE)));
    }

    static Skill initialSkill() {
        return new Skill(INITIAL_MEAN, INITIAL_DEVIATION);
    }

    private static double priorVariance(Skill skill) {
        return skill.deviation() * skill.deviation() + DYNAMIC_FACTOR * DYNAMIC_FACTOR;
    }

    private static double normalPdf(double value) {
        return Math.exp(-0.5 * value * value) / Math.sqrt(2.0 * Math.PI);
    }

    // Abramowitz-Stegun 26.2.17; sufficient precision for the TrueSkill correction term.
    private static double normalCdf(double value) {
        if (value < 0) return 1.0 - normalCdf(-value);
        double t = 1.0 / (1.0 + 0.2316419 * value);
        double polynomial = t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t * 1.330274429))));
        return 1.0 - normalPdf(value) * polynomial;
    }

    record Skill(double mean, double deviation) {
        Skill {
            if (!Double.isFinite(mean) || !Double.isFinite(deviation) || deviation <= 0) {
                throw new IllegalArgumentException("A TrueSkill distribution requires finite μ and positive σ");
            }
        }
    }

    record Competitor(UUID playerId, TeamSide team, Skill skill) {
        Competitor {
            if (playerId == null || team == null || skill == null) {
                throw new IllegalArgumentException("A TrueSkill competitor is incomplete");
            }
        }
    }
}
