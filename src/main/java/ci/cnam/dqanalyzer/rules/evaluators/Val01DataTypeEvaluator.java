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
import java.util.Locale;
import org.springframework.stereotype.Component;

/** VAL-01 — Type de donnée : entier, décimal, date, booléen ou texte. */
@Component
public class Val01DataTypeEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("dataType", ParamType.TEXT, true, "param.dataType"),
                new Param("dateFormat", ParamType.TEXT, false, "param.dateFormat"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String column = data.quotedColumn(rule.firstColumn());
        String text = "trim(CAST(" + column + " AS VARCHAR))";
        return switch (params.text("dataType").toLowerCase(Locale.ROOT)) {
            case "entier", "integer" -> SqlFragment.of("TRY_CAST(" + text + " AS BIGINT) IS NULL");
            case "decimal", "décimal" -> SqlFragment.of(Sql.number(column) + " IS NULL");
            case "date" ->
                SqlFragment.of(
                        Sql.date(column) + " IS NULL",
                        DateFormats.toDuckDb(params.textOrDefault("dateFormat", DateFormats.DEFAULT)));
            case "booleen", "booléen", "boolean" ->
                SqlFragment.of("lower(" + text
                        + ") NOT IN ('true', 'false', 'vrai', 'faux', 'oui', 'non', 'o', 'n', '1', '0')");
            case "texte", "text" -> SqlFragment.of("FALSE");
            default ->
                throw new RuleEvaluationException(
                        "Règle " + rule.ruleCode() + " : type inconnu (entier, décimal, date, booléen ou texte)");
        };
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » n'est pas du type attendu ("
                + RuleParams.of(rule).text("dataType") + ").";
    }
}
