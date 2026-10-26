package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lecture des paramètres JSON d'une règle. Les valeurs viennent d'un formulaire ou d'un import :
 * un nombre peut donc arriver en texte. Un paramètre inutilisable produit une erreur nommant le
 * paramètre fautif, lisible par l'analyste (ERG-02).
 */
final class RuleParams {

    private RuleParams() {}

    /** Entier, ou null si le paramètre est absent ou vide. */
    static Integer optionalInteger(RuleDefinition rule, String name) {
        Object value = raw(rule, name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.intValue() == number.longValue()) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw invalid(rule, name, "un nombre entier");
        }
    }

    /** Nombre décimal, ou null si le paramètre est absent ou vide. */
    static BigDecimal optionalNumber(RuleDefinition rule, String name) {
        Object value = raw(rule, name);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException e) {
            throw invalid(rule, name, "un nombre");
        }
    }

    /** Liste de textes non vide, saisie en liste ou en texte séparé par des virgules. */
    static List<String> requiredTextList(RuleDefinition rule, String name) {
        Object value = raw(rule, name);
        var values = new ArrayList<String>();
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                addIfNotBlank(values, item);
            }
        } else if (value != null) {
            Arrays.stream(value.toString().split(",")).forEach(item -> addIfNotBlank(values, item));
        }
        if (values.isEmpty()) {
            throw new RuleEvaluationException("Le paramètre « " + name + " » de la règle " + rule.ruleCode()
                    + " doit contenir au moins une valeur");
        }
        return List.copyOf(values);
    }

    /** Erreur signalant qu'aucun des paramètres attendus n'est renseigné. */
    static RuleEvaluationException noneOf(RuleDefinition rule, String... names) {
        String expected = Arrays.stream(names).map(name -> "« " + name + " »").collect(Collectors.joining(" ou "));
        return new RuleEvaluationException(
                "La règle " + rule.ruleCode() + " attend au moins un des paramètres " + expected);
    }

    private static void addIfNotBlank(List<String> values, Object item) {
        if (item != null && !item.toString().isBlank()) {
            values.add(item.toString().trim());
        }
    }

    private static Object raw(RuleDefinition rule, String name) {
        Object value = rule.params().get(name);
        return value == null || value.toString().isBlank() ? null : value;
    }

    private static RuleEvaluationException invalid(RuleDefinition rule, String name, String expected) {
        return new RuleEvaluationException(
                "Le paramètre « " + name + " » de la règle " + rule.ruleCode() + " doit être " + expected);
    }
}
