package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/** CMP-01 — Champ obligatoire non vide. Une valeur absente ou composée uniquement d'espaces est en anomalie. */
@Component
public class Cmp01RequiredValueEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "CMP-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.COMPLETENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn();
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        String column = data.quotedColumn(rule.firstColumn());
        return SqlFragment.of(column + " IS NULL OR trim(CAST(" + column + " AS VARCHAR)) = ''");
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La colonne « " + rule.firstColumn() + " » doit être renseignée.";
    }
}
