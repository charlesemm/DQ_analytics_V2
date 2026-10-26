package ci.cnam.dqanalyzer.rules.expression;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calcul saisi par l'analyste pour COH-03 (« total = quantité × prix unitaire »), sous forme d'arbre JSON :
 * <pre>{"op": "mul", "args": [ {"column": "quantite"}, {"column": "prix_unitaire"} ]}</pre>
 * Opérations : add, sub, mul, div ; feuilles : {"column": …} ou {"value": nombre}.
 */
public final class NumericExpression {

    private static final Map<String, String> OPERATORS = Map.of("add", "+", "sub", "-", "mul", "*", "div", "/");
    private static final int MAX_DEPTH = 5;

    private NumericExpression() {}

    /** SQL de l'expression ; NULL si une des cellules n'est pas un nombre ou en cas de division par zéro. */
    public static SqlFragment compile(Object json, Dataset data) {
        return compile(json, data, 0);
    }

    /** Colonnes utilisées par l'expression, pour restreindre les lignes contrôlées à celles qui sont remplies. */
    public static Set<String> columns(Object json) {
        var result = new java.util.LinkedHashSet<String>();
        collect(json, result, 0);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static SqlFragment compile(Object json, Dataset data, int depth) {
        if (depth > MAX_DEPTH) {
            throw new RuleEvaluationException("Calcul trop imbriqué (" + MAX_DEPTH + " niveaux au plus)");
        }
        if (!(json instanceof Map<?, ?> raw)) {
            throw new RuleEvaluationException("Calcul invalide : un objet est attendu");
        }
        var map = (Map<String, Object>) raw;
        if (map.containsKey("column")) {
            String column = data.quotedColumn(String.valueOf(map.get("column")));
            return SqlFragment.of("TRY_CAST(replace(trim(CAST(" + column + " AS VARCHAR)), ',', '.') AS DOUBLE)");
        }
        if (map.containsKey("value")) {
            try {
                return SqlFragment.of(
                        "CAST(? AS DOUBLE)",
                        Double.parseDouble(String.valueOf(map.get("value")).replace(',', '.')));
            } catch (NumberFormatException e) {
                throw new RuleEvaluationException("Calcul invalide : « " + map.get("value") + " » n'est pas un nombre");
            }
        }
        String op = OPERATORS.get(String.valueOf(map.get("op")));
        if (op == null || !(map.get("args") instanceof List<?> args) || args.size() != 2) {
            throw new RuleEvaluationException(
                    "Calcul invalide : op doit valoir add, sub, mul ou div, avec 2 arguments");
        }
        var left = compile(args.get(0), data, depth + 1);
        var right = compile(args.get(1), data, depth + 1);
        var params = new ArrayList<>(left.params());
        params.addAll(right.params());
        String rightSql = op.equals("/") ? "nullif(" + right.sql() + ", 0)" : right.sql();
        return new SqlFragment("(" + left.sql() + " " + op + " " + rightSql + ")", params);
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object json, Set<String> result, int depth) {
        if (depth > MAX_DEPTH || !(json instanceof Map<?, ?> raw)) {
            return;
        }
        var map = (Map<String, Object>) raw;
        if (map.containsKey("column")) {
            result.add(String.valueOf(map.get("column")));
        } else if (map.get("args") instanceof List<?> args) {
            args.forEach(arg -> collect(arg, result, depth + 1));
        }
    }
}
