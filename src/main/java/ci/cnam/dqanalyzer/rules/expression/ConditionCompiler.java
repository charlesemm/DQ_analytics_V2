package ci.cnam.dqanalyzer.rules.expression;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.expression.Condition.All;
import ci.cnam.dqanalyzer.rules.expression.Condition.Any;
import ci.cnam.dqanalyzer.rules.expression.Condition.Comparison;
import ci.cnam.dqanalyzer.rules.expression.Condition.Not;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Traduit une {@link Condition} en SQL DuckDB. Les colonnes passent par {@link Dataset#quotedColumn}
 * (existence vérifiée, nom cité) et les valeurs deviennent des paramètres « ? ».
 *
 * <p>Une condition qui ne peut pas être évaluée (cellule vide, texte au lieu d'un nombre) vaut FAUX,
 * jamais « inconnu » : {@code coalesce(…, false)}.
 */
public final class ConditionCompiler {

    private ConditionCompiler() {}

    public static SqlFragment compile(Condition condition, Dataset data) {
        return switch (condition) {
            case All all -> join(all.conditions(), " AND ", data);
            case Any any -> join(any.conditions(), " OR ", data);
            case Not not -> {
                var inner = compile(not.condition(), data);
                yield new SqlFragment("NOT (" + inner.sql() + ")", inner.params());
            }
            case Comparison c -> comparison(c, data);
        };
    }

    private static SqlFragment join(List<Condition> conditions, String operator, Dataset data) {
        var parts = new ArrayList<String>();
        var params = new ArrayList<Object>();
        for (Condition child : conditions) {
            var fragment = compile(child, data);
            parts.add("(" + fragment.sql() + ")");
            params.addAll(fragment.params());
        }
        return new SqlFragment(String.join(operator, parts), params);
    }

    private static SqlFragment comparison(Comparison c, Dataset data) {
        String column = data.quotedColumn(c.column());
        String text = "trim(CAST(" + column + " AS VARCHAR))";
        switch (c.op()) {
            case EMPTY -> {
                return SqlFragment.of("coalesce(" + text + ", '') = ''");
            }
            case FILLED -> {
                return SqlFragment.of("coalesce(" + text + ", '') <> ''");
            }
            default -> {
                // comparaison : traitée plus bas
            }
        }

        var params = new ArrayList<Object>();
        String left;
        String placeholder;
        switch (c.valueType()) {
            case NUMBER -> {
                left = "TRY_CAST(replace(" + text + ", ',', '.') AS DOUBLE)";
                placeholder = "CAST(? AS DOUBLE)";
                for (String value : c.values()) {
                    params.add(parseNumber(value, c.column()));
                }
            }
            case DATE -> {
                params.add(DateFormats.toDuckDb(c.dateFormat()));
                left = "CAST(try_strptime(" + text + ", ?) AS DATE)";
                placeholder = "CAST(? AS DATE)";
                for (String value : c.values()) {
                    params.add(isoDate(value, c.column()));
                }
            }
            default -> {
                left = text;
                placeholder = "?";
                params.addAll(c.values());
            }
        }

        String sql =
                switch (c.op()) {
                    case IN, NOT_IN ->
                        left + " " + c.op().sql() + " ("
                                + String.join(
                                        ", ", Collections.nCopies(c.values().size(), placeholder)) + ")";
                    default -> left + " " + c.op().sql() + " " + placeholder;
                };
        return new SqlFragment("coalesce(" + sql + ", false)", params);
    }

    private static Double parseNumber(String value, String column) {
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new RuleEvaluationException(
                    "Condition sur « " + column + " » : « " + value + " » n'est pas un nombre");
        }
    }

    /** La valeur d'une condition de date s'écrit jj/mm/aaaa ou aaaa-mm-jj ; elle est passée à DuckDB en ISO. */
    private static String isoDate(String value, String column) {
        String v = value.trim();
        if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return v;
        }
        if (v.matches("\\d{2}/\\d{2}/\\d{4}")) {
            return v.substring(6) + "-" + v.substring(3, 5) + "-" + v.substring(0, 2);
        }
        throw new RuleEvaluationException(
                "Condition sur « " + column + " » : la date « " + value + " » doit s'écrire jj/mm/aaaa");
    }
}
