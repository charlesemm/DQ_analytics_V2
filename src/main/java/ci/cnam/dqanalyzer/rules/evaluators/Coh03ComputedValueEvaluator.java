package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.rules.expression.NumericExpression;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * COH-03 — Valeur calculée : « total = quantité × prix unitaire », à une tolérance près (0,01 par défaut)
 * pour absorber les arrondis. Contrôlé quand la cible et toutes les colonnes du calcul sont remplies.
 */
@Component
public class Coh03ComputedValueEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "COH-03";
    }

    @Override
    public Dimension dimension() {
        return Dimension.CONSISTENCY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("expression", ParamType.EXPRESSION, true, "param.expression"),
                new Param("tolerance", ParamType.DECIMAL, false, "param.tolerance"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        var scope = Sql.filled(data.quotedColumn(rule.firstColumn()));
        for (String column : NumericExpression.columns(RuleParams.of(rule).object("expression"))) {
            scope = scope.and(Sql.filled(data.quotedColumn(column)));
        }
        return scope;
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        var expression = NumericExpression.compile(params.object("expression"), data);
        String target = Sql.number(data.quotedColumn(rule.firstColumn()));
        var values = new ArrayList<Object>(expression.params());
        values.add(params.optionalNumber("tolerance").orElse(0.01));
        return new SqlFragment(
                "coalesce(abs(" + target + " - " + expression.sql() + ") > ?, true)", List.copyOf(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » ne correspond pas au calcul attendu.";
    }
}
