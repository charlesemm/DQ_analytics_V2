package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/** INT-01 — Existence dans une table de référence : « le code commune existe au référentiel des communes ». */
@Component
public class Int01ReferenceExistsEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "INT-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.REFERENTIAL_INTEGRITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("referenceTableId", ParamType.REFERENCE_TABLE, true, "param.referenceTable"),
                new Param("referenceColumn", ParamType.TEXT, true, "param.referenceColumn"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String ref = DuckDbIdentifiers.quote(refs.duckDbTable(params.longValue("referenceTableId")));
        String refColumn = "r." + DuckDbIdentifiers.quote(params.text("referenceColumn"));
        String value = data.quotedTable() + "." + data.quotedColumn(rule.firstColumn());
        var norm = rule.normalization();
        return SqlFragment.of("NOT EXISTS (SELECT 1 FROM " + ref + " r WHERE " + norm.apply(refColumn) + " = "
                + norm.apply(value) + ")");
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » n'existe pas dans la table de référence.";
    }
}
