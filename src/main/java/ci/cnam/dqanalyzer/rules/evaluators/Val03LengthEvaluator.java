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

/** VAL-03 — Longueur comprise entre un minimum et un maximum (l'un des deux peut manquer). */
@Component
public class Val03LengthEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-03";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("min", ParamType.INTEGER, false, "param.minLength"),
                new Param("max", ParamType.INTEGER, false, "param.maxLength"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        var min = params.optionalNumber("min");
        var max = params.optionalNumber("max");
        if (min.isEmpty() && max.isEmpty()) {
            throw new RuleEvaluationException(
                    "Règle " + rule.ruleCode() + " : indique une longueur minimale ou maximale");
        }
        String length = "length(" + normalizedValue(data, rule) + ")";
        var parts = new ArrayList<String>();
        var values = new ArrayList<Object>();
        min.ifPresent(m -> {
            parts.add(length + " < ?");
            values.add(m.longValue());
        });
        max.ifPresent(m -> {
            parts.add(length + " > ?");
            values.add(m.longValue());
        });
        return new SqlFragment(String.join(" OR ", parts), List.copyOf(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La longueur de « " + rule.firstColumn() + " » n'est pas celle attendue.";
    }
}
