package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lecture typée des paramètres JSON d'une règle, avec des messages d'erreur lisibles par l'analyste.
 * Les valeurs viennent de Jackson : texte, nombre, booléen, liste ou objet.
 */
public final class RuleParams {

    private final String ruleCode;
    private final Map<String, Object> values;

    private RuleParams(String ruleCode, Map<String, Object> values) {
        this.ruleCode = ruleCode;
        this.values = values;
    }

    public static RuleParams of(RuleDefinition rule) {
        return new RuleParams(rule.ruleCode(), rule.params());
    }

    public String text(String name) {
        return optionalText(name).orElseThrow(() -> missing(name));
    }

    public Optional<String> optionalText(String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.toString());
    }

    public String textOrDefault(String name, String defaultValue) {
        return optionalText(name).orElse(defaultValue);
    }

    public double number(String name) {
        return optionalNumber(name).orElseThrow(() -> missing(name));
    }

    public Optional<Double> optionalNumber(String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        if (value instanceof Number n) {
            return Optional.of(n.doubleValue());
        }
        try {
            return Optional.of(Double.parseDouble(value.toString().replace(',', '.')));
        } catch (NumberFormatException e) {
            throw new RuleEvaluationException("Règle " + ruleCode + " : le paramètre « " + name
                    + " » doit être un nombre (valeur : " + value + ")");
        }
    }

    public List<String> textList(String name) {
        Object value = values.get(name);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value != null && !value.toString().isBlank()) {
            // Liste saisie sous forme de texte : « M; F » ou « M, F »
            return List.of(value.toString().split("\\s*[;,]\\s*"));
        }
        throw missing(name);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> object(String name) {
        Object value = values.get(name);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw missing(name);
    }

    public long longValue(String name) {
        return (long) number(name);
    }

    private RuleEvaluationException missing(String name) {
        return new RuleEvaluationException("Règle " + ruleCode + " : le paramètre « " + name + " » est obligatoire");
    }
}
