package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/** CMP-02 — Valeur bouche-trou interdite (N/A, 0, XXX, 01/01/1900…) : une valeur qui remplit la case sans rien dire. */
@Component
public class Cmp02PlaceholderValueEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "CMP-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.COMPLETENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("values", ParamType.TEXT_LIST, true, "param.placeholders"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var values = RuleParams.of(rule).textList("values");
        String value = normalizedValue(data, rule);
        String list = Sql.placeholders(values.size(), rule.normalization()::apply);
        return new SqlFragment(value + " IN (" + list + ")", new ArrayList<>(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La colonne « " + rule.firstColumn() + " » contient une valeur de remplissage, pas une vraie valeur.";
    }
}
