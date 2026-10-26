package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.rules.expression.ConditionCompiler;
import ci.cnam.dqanalyzer.rules.expression.ConditionParser;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/**
 * CMP-03 — Obligatoire sous condition : « si statut = Ayant droit, le n° d'assuré principal est renseigné ».
 * Seules les lignes qui remplissent la condition sont contrôlées.
 */
@Component
public class Cmp03ConditionalRequiredEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "CMP-03";
    }

    @Override
    public Dimension dimension() {
        return Dimension.COMPLETENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("when", ParamType.EXPRESSION, true, "param.when"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        var condition = ConditionParser.parse(RuleParams.of(rule).object("when"));
        return ConditionCompiler.compile(condition, data);
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        return Sql.empty(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La colonne « " + rule.firstColumn() + " » doit être renseignée pour cette ligne.";
    }
}
