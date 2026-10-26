package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
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
import org.springframework.stereotype.Component;

/** VAL-04 — Plage de valeurs numériques : « âge entre 0 et 120 ». Une valeur non numérique est hors plage. */
@Component
public class Val04RangeEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-04";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("min", ParamType.DECIMAL, false, "param.min"),
                new Param("max", ParamType.DECIMAL, false, "param.max"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        var min = params.optionalNumber("min");
        var max = params.optionalNumber("max");
        if (min.isEmpty() && max.isEmpty()) {
            throw new RuleEvaluationException("Règle " + rule.ruleCode() + " : indique un minimum ou un maximum");
        }
        String number = Sql.number(data.quotedColumn(rule.firstColumn()));
        var parts = new ArrayList<String>();
        parts.add(number + " IS NULL");
        var values = new ArrayList<Object>();
        min.ifPresent(m -> {
            parts.add(number + " < ?");
            values.add(m);
        });
        max.ifPresent(m -> {
            parts.add(number + " > ?");
            values.add(m);
        });
        return new SqlFragment(String.join(" OR ", parts), List.copyOf(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » est hors de la plage autorisée.";
    }
}
