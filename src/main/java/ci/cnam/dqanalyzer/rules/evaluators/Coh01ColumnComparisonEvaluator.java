package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * COH-01 — Comparaison entre deux colonnes d'une même ligne : « date de début ≤ date de fin ».
 * Contrôlé seulement quand les deux cellules sont remplies. Une valeur qu'on ne peut pas convertir
 * (date mal écrite, texte au lieu d'un nombre) est en anomalie.
 */
@Component
public class Coh01ColumnComparisonEvaluator extends RowPredicateEvaluator {

    private static final Map<String, String> OPERATORS =
            Map.of("lt", "<", "lte", "<=", "eq", "=", "ne", "<>", "gt", ">", "gte", ">=");

    @Override
    public String type() {
        return "COH-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.CONSISTENCY;
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(
                ParamSchema.ColumnArity.TWO,
                List.of(
                        new Param("operator", ParamType.TEXT, true, "param.operator"),
                        new Param("valueType", ParamType.TEXT, true, "param.valueType"),
                        new Param("dateFormat", ParamType.TEXT, false, "param.dateFormat")));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return Sql.filled(data.quotedColumn(rule.firstColumn()))
                .and(Sql.filled(data.quotedColumn(rule.secondColumn())));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String operator = OPERATORS.get(params.text("operator").toLowerCase(Locale.ROOT));
        if (operator == null) {
            throw new RuleEvaluationException(
                    "Règle " + rule.ruleCode() + " : opérateur inconnu (lt, lte, eq, ne, gt, gte)");
        }
        String a = data.quotedColumn(rule.firstColumn());
        String b = data.quotedColumn(rule.secondColumn());
        var values = new ArrayList<Object>();
        String left;
        String right;
        switch (params.text("valueType").toLowerCase(Locale.ROOT)) {
            case "number", "nombre" -> {
                left = Sql.number(a);
                right = Sql.number(b);
            }
            case "date" -> {
                String format = DateFormats.toDuckDb(params.textOrDefault("dateFormat", DateFormats.DEFAULT));
                left = Sql.date(a);
                right = Sql.date(b);
                values.add(format);
                values.add(format);
            }
            case "text", "texte" -> {
                left = rule.normalization().apply(a);
                right = rule.normalization().apply(b);
            }
            default ->
                throw new RuleEvaluationException(
                        "Règle " + rule.ruleCode() + " : type inconnu (number, date ou text)");
        }
        return new SqlFragment("NOT coalesce(" + left + " " + operator + " " + right + ", false)", values);
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » et « " + rule.secondColumn() + " » sont incohérentes entre elles.";
    }
}
