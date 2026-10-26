package ci.cnam.dqanalyzer.rules.expression;

import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.expression.Condition.Comparison;
import ci.cnam.dqanalyzer.rules.expression.Condition.Operator;
import ci.cnam.dqanalyzer.rules.expression.Condition.ValueType;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Transforme le JSON d'une condition (déjà lu par Jackson en Map) en arbre {@link Condition}. */
public final class ConditionParser {

    private static final int MAX_DEPTH = 5;

    private ConditionParser() {}

    public static Condition parse(Object json) {
        return parse(json, 0);
    }

    @SuppressWarnings("unchecked")
    private static Condition parse(Object json, int depth) {
        if (depth > MAX_DEPTH) {
            throw new RuleEvaluationException("Condition trop imbriquée (" + MAX_DEPTH + " niveaux au plus)");
        }
        if (!(json instanceof Map<?, ?> raw)) {
            throw new RuleEvaluationException("Condition invalide : un objet est attendu");
        }
        var map = (Map<String, Object>) raw;
        if (map.containsKey("all")) {
            return new Condition.All(children(map.get("all"), depth));
        }
        if (map.containsKey("any")) {
            return new Condition.Any(children(map.get("any"), depth));
        }
        if (map.containsKey("not")) {
            return new Condition.Not(parse(map.get("not"), depth + 1));
        }
        return comparison(map);
    }

    private static List<Condition> children(Object list, int depth) {
        if (!(list instanceof List<?> items) || items.isEmpty()) {
            throw new RuleEvaluationException("Condition invalide : « all » et « any » attendent une liste non vide");
        }
        return items.stream().map(item -> parse(item, depth + 1)).toList();
    }

    private static Comparison comparison(Map<String, Object> map) {
        Object column = map.get("column");
        if (column == null || column.toString().isBlank()) {
            throw new RuleEvaluationException("Condition invalide : la colonne est obligatoire");
        }
        Operator op = enumValue(Operator.class, map.getOrDefault("op", "eq"), "opérateur");
        ValueType type = enumValue(ValueType.class, map.getOrDefault("as", "text"), "type");

        List<String> values;
        Object value = map.get("value");
        if (op == Operator.EMPTY || op == Operator.FILLED) {
            values = List.of();
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            values = list.stream().map(String::valueOf).toList();
        } else if (value != null) {
            values = List.of(value.toString());
        } else {
            throw new RuleEvaluationException("Condition sur « " + column + " » : une valeur est attendue");
        }
        boolean multiValue = op == Operator.IN || op == Operator.NOT_IN;
        if (!multiValue && values.size() > 1) {
            throw new RuleEvaluationException("Condition sur « " + column + " » : une seule valeur est attendue");
        }
        String dateFormat = String.valueOf(map.getOrDefault("dateFormat", DateFormats.DEFAULT));
        return new Comparison(column.toString(), op, values, type, dateFormat);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object raw, String label) {
        try {
            return Enum.valueOf(type, raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RuleEvaluationException("Condition invalide : " + label + " inconnu « " + raw + " »");
        }
    }
}
