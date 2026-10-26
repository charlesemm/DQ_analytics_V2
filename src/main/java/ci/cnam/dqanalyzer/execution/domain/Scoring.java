package ci.cnam.dqanalyzer.execution.domain;

import ci.cnam.dqanalyzer.execution.RuleStatus;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleResult;
import ci.cnam.dqanalyzer.rules.Severity;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Calcul des scores et statuts (CDC §7). Seul endroit du code où ce calcul existe.
 *
 * <p>Choix à valider avec le métier : une règle qui ne contrôle aucune ligne (0 ligne concernée)
 * n'a pas de score et n'entre pas dans les moyennes ; son statut est OK.
 */
public final class Scoring {

    private final Map<Severity, Integer> severityWeights;

    /** Poids par défaut : Bloquante 3, Majeure 2, Mineure 1. */
    public Scoring() {
        this(defaultWeights());
    }

    /** Poids personnalisés (modifiables par l'administrateur). */
    public Scoring(Map<Severity, Integer> severityWeights) {
        var weights = new EnumMap<Severity, Integer>(Severity.class);
        for (Severity severity : Severity.values()) {
            int weight = Objects.requireNonNull(severityWeights.get(severity), "Poids manquant pour " + severity);
            if (weight <= 0) {
                throw new IllegalArgumentException("Le poids de " + severity + " doit être positif");
            }
            weights.put(severity, weight);
        }
        this.severityWeights = Map.copyOf(weights);
    }

    private static Map<Severity, Integer> defaultWeights() {
        var weights = new EnumMap<Severity, Integer>(Severity.class);
        for (Severity severity : Severity.values()) {
            weights.put(severity, severity.defaultWeight());
        }
        return weights;
    }

    /** Score d'une règle = conformes / contrôlées × 100 ; vide si aucune ligne contrôlée. */
    public OptionalDouble ruleScore(RuleResult result) {
        if (result.checkedRows() == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(result.conformingRows() * 100.0 / result.checkedRows());
    }

    public RuleStatus ruleStatus(OptionalDouble score, double thresholdPercent, Severity severity) {
        if (score.isEmpty() || score.getAsDouble() >= thresholdPercent) {
            return RuleStatus.OK;
        }
        return severity == Severity.BLOCKING ? RuleStatus.KO : RuleStatus.ALERT;
    }

    /** Score d'une dimension = moyenne des scores de ses règles pondérée par la sévérité. */
    public Map<Dimension, Double> dimensionScores(List<ScoredRule> rules) {
        var sums = new EnumMap<Dimension, double[]>(Dimension.class);
        for (ScoredRule rule : rules) {
            if (rule.score().isEmpty()) {
                continue;
            }
            int weight = severityWeights.get(rule.severity());
            double[] acc = sums.computeIfAbsent(rule.dimension(), d -> new double[2]);
            acc[0] += weight * rule.score().getAsDouble();
            acc[1] += weight;
        }
        var scores = new EnumMap<Dimension, Double>(Dimension.class);
        sums.forEach((dimension, acc) -> scores.put(dimension, acc[0] / acc[1]));
        return scores;
    }

    /**
     * Score global = moyenne des dimensions mesurées, pondérée par les poids du modèle (1 par défaut).
     * Une dimension absente de {@code dimensionScores} est « Non mesurée » et n'entre pas dans le calcul.
     */
    public OptionalDouble globalScore(Map<Dimension, Double> dimensionScores, Map<Dimension, Double> dimensionWeights) {
        double sum = 0;
        double totalWeight = 0;
        for (var entry : dimensionScores.entrySet()) {
            double weight = dimensionWeights.getOrDefault(entry.getKey(), 1.0);
            sum += weight * entry.getValue();
            totalWeight += weight;
        }
        return totalWeight == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / totalWeight);
    }

    /** Score d'une colonne : même calcul pondéré, restreint aux règles portant sur la colonne. */
    public OptionalDouble columnScore(String column, List<ScoredRule> rules) {
        double sum = 0;
        double totalWeight = 0;
        for (ScoredRule rule : rules) {
            if (rule.columns().contains(column) && rule.score().isPresent()) {
                int weight = severityWeights.get(rule.severity());
                sum += weight * rule.score().getAsDouble();
                totalWeight += weight;
            }
        }
        return totalWeight == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / totalWeight);
    }

    /** KO dès qu'une règle Bloquante est KO, sinon Alerte s'il existe une Alerte, sinon OK. */
    public RuleStatus globalStatus(Collection<RuleStatus> ruleStatuses) {
        if (ruleStatuses.contains(RuleStatus.KO)) {
            return RuleStatus.KO;
        }
        return ruleStatuses.contains(RuleStatus.ALERT) ? RuleStatus.ALERT : RuleStatus.OK;
    }

    /** Une règle exécutée, réduite à ce dont le calcul a besoin. */
    public record ScoredRule(Dimension dimension, Severity severity, List<String> columns, OptionalDouble score) {
        public ScoredRule {
            columns = List.copyOf(columns);
        }
    }
}
