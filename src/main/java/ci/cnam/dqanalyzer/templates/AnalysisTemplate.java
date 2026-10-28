package ci.cnam.dqanalyzer.templates;

import ci.cnam.dqanalyzer.rules.Dimension;
import java.util.List;
import java.util.Map;

/**
 * Un modèle d'analyse (MOD-01) : les colonnes attendues du fichier, les règles retenues dans
 * l'ordre, le poids de chaque dimension dans le score global, et la correspondance mémorisée entre
 * les colonnes attendues et celles du dernier fichier associé (MOD-02, MOD-03).
 *
 * @param columnMapping colonne attendue → colonne du fichier
 */
public record AnalysisTemplate(
        Long id,
        String name,
        String description,
        List<String> expectedColumns,
        Map<Dimension, Integer> dimensionWeights,
        List<String> ruleCodes,
        Map<String, String> columnMapping) {

    public AnalysisTemplate {
        expectedColumns = List.copyOf(expectedColumns);
        dimensionWeights = Map.copyOf(dimensionWeights);
        ruleCodes = List.copyOf(ruleCodes);
        columnMapping = Map.copyOf(columnMapping);
    }

    /** Poids d'une dimension, 1 par défaut : un modèle n'a pas à les déclarer tous. */
    public int weightOf(Dimension dimension) {
        return dimensionWeights.getOrDefault(dimension, 1);
    }
}
