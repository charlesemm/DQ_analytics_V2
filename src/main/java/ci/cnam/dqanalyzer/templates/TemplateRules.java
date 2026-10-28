package ci.cnam.dqanalyzer.templates;

import ci.cnam.dqanalyzer.rules.RuleDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapte les règles d'un modèle au fichier analysé (MOD-03) : les règles désignent les colonnes
 * attendues (« Date de naissance »), le fichier porte les siennes (« DATE_DE_NAISSANCE »). Sans
 * cette traduction, chaque règle échouerait sur une colonne introuvable.
 *
 * <p>Appelé juste avant l'exécution d'une analyse, sur les {@link RuleDefinition} lues en base : les
 * règles enregistrées ne sont pas modifiées.
 */
public final class TemplateRules {

    private TemplateRules() {}

    /**
     * @param mapping colonne attendue → colonne du fichier ; une colonne absente du mapping est
     *     laissée telle quelle, car elle porte déjà le nom du fichier
     */
    public static List<RuleDefinition> remap(List<RuleDefinition> rules, Map<String, String> mapping) {
        if (mapping.isEmpty()) {
            return List.copyOf(rules);
        }
        return rules.stream().map(rule -> remap(rule, mapping)).toList();
    }

    private static RuleDefinition remap(RuleDefinition rule, Map<String, String> mapping) {
        var columns = rule.targetColumns().stream()
                .map(column -> mapping.getOrDefault(column, column))
                .toList();
        var params = remapParams(rule.params(), mapping);
        if (columns.equals(rule.targetColumns()) && params.equals(rule.params())) {
            return rule;
        }
        return new RuleDefinition(
                rule.id(),
                rule.ruleCode(),
                rule.version(),
                rule.name(),
                rule.type(),
                rule.dimension(),
                columns,
                params,
                rule.normalization(),
                rule.severity(),
                rule.thresholdPercent(),
                rule.message());
    }

    /**
     * Les conditions (CMP-03, COH-02) et les calculs (COH-03) citent aussi des colonnes, dans leurs
     * paramètres JSON : « {"column": "Date de naissance", …} ». Elles sont traduites de la même
     * façon, à toute profondeur, et rien d'autre n'est touché.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> remapParams(Map<String, Object> params, Map<String, String> mapping) {
        var result = new LinkedHashMap<String, Object>();
        params.forEach((name, value) -> result.put(name, remapValue(name, value, mapping)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object remapValue(String name, Object value, Map<String, String> mapping) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            ((Map<String, Object>) map)
                    .forEach((key, nested) -> result.put(
                            key,
                            "column".equals(key) && nested != null
                                    ? mapping.getOrDefault(nested.toString(), nested.toString())
                                    : remapValue(key, nested, mapping)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> remapValue(name, item, mapping)).toList();
        }
        return value;
    }
}
