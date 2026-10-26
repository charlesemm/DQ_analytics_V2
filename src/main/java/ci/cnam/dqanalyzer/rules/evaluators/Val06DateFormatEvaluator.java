package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * VAL-06 — Format de date : la valeur doit être une date réelle dans l'un des formats acceptés.
 * « 31/02/2024 » est refusé : try_strptime vérifie que la date existe, pas seulement sa forme.
 */
@Component
public class Val06DateFormatEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-06";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("formats", ParamType.TEXT_LIST, true, "param.dateFormats"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        List<String> formats = RuleParams.of(rule).textList("formats").stream()
                .map(DateFormats::toDuckDb)
                .toList();
        String text = "trim(CAST(" + data.quotedColumn(rule.firstColumn()) + " AS VARCHAR))";
        String sql = String.join(" AND ", Collections.nCopies(formats.size(), "try_strptime(" + text + ", ?) IS NULL"));
        return new SqlFragment(sql, List.copyOf(formats));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » ne contient pas une date valide au format attendu ("
                + String.join(" ou ", RuleParams.of(rule).textList("formats")) + ").";
    }
}
