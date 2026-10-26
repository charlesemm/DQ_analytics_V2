package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/**
 * CMP-04 — Taux de remplissage minimal : « téléphone renseigné sur au moins 80 % des lignes ».
 * Le score de la règle EST le taux de remplissage ; le pourcentage minimal est le seuil de la règle.
 * Contrairement à CMP-01, une cellule vide n'est pas une faute en soi : c'est leur proportion qui compte.
 */
@Component
public class Cmp04FillRateEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "CMP-04";
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
        return Sql.empty(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "Cellule vide dans « " + rule.firstColumn() + " » (le taux de remplissage attendu est de "
                + rule.thresholdPercent() + " %).";
    }
}
