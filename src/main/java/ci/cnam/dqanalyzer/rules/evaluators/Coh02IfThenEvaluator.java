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
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * COH-02 — Règle conditionnelle SI… ALORS : « si sexe = M, pas de prestation maternité ».
 * Lignes contrôlées = celles qui remplissent le SI ; anomalie = le ALORS est faux.
 * La colonne indiquée sert seulement à afficher la valeur dans le rapport.
 */
@Component
public class Coh02IfThenEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "COH-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.CONSISTENCY;
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(
                ParamSchema.ColumnArity.ONE,
                List.of(
                        new Param("when", ParamType.EXPRESSION, true, "param.when"),
                        new Param("then", ParamType.EXPRESSION, true, "param.then")));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return ConditionCompiler.compile(
                ConditionParser.parse(RuleParams.of(rule).object("when")), data);
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var then = ConditionCompiler.compile(
                ConditionParser.parse(RuleParams.of(rule).object("then")), data);
        return new SqlFragment("NOT (" + then.sql() + ")", then.params());
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La ligne ne respecte pas la règle « " + rule.name() + " ».";
    }
}
