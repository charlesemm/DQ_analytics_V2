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

/** VAL-05 — Liste de valeurs autorisées : « sexe ∈ {M, F} ». La normalisation s'applique aux deux côtés. */
@Component
public class Val05AllowedValuesEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-05";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("values", ParamType.TEXT_LIST, true, "param.allowedValues"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var values = RuleParams.of(rule).textList("values");
        String list = Sql.placeholders(values.size(), rule.normalization()::apply);
        return new SqlFragment(normalizedValue(data, rule) + " NOT IN (" + list + ")", new ArrayList<>(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » ne fait pas partie des valeurs autorisées ("
                + String.join(", ", RuleParams.of(rule).textList("values")) + ").";
    }
}
