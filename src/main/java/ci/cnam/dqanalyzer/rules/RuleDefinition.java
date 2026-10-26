package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Une version précise d'une règle, telle qu'elle est exécutée. Objet immuable, indépendant de JPA.
 *
 * @param thresholdPercent seuil d'acceptation entre 0 et 100
 * @param params paramètres propres au type de règle (stockés en JSON)
 */
public record RuleDefinition(
        Long id,
        String ruleCode,
        int version,
        String name,
        String type,
        Dimension dimension,
        List<String> targetColumns,
        Map<String, Object> params,
        Normalization normalization,
        Severity severity,
        double thresholdPercent,
        String message) {

    public RuleDefinition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(severity, "severity");
        targetColumns = List.copyOf(targetColumns);
        params = params == null ? Map.of() : Map.copyOf(params);
        normalization = normalization == null ? Normalization.NONE : normalization;
        if (thresholdPercent < 0 || thresholdPercent > 100) {
            throw new IllegalArgumentException("Le seuil doit être compris entre 0 et 100");
        }
    }

    /** Première colonne ciblée : la plupart des règles n'en ont qu'une. */
    public String firstColumn() {
        if (targetColumns.isEmpty()) {
            throw new RuleEvaluationException("La règle " + ruleCode + " ne cible aucune colonne");
        }
        return targetColumns.getFirst();
    }
}
