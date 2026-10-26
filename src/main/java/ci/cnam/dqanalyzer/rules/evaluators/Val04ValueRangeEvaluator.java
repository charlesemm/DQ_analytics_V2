package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * VAL-04 — Plage de valeurs, entre {@code min} et {@code max} bornes incluses (au moins une des deux
 * bornes). Seules les valeurs renseignées sont contrôlées : une valeur absente relève de CMP-01. Une
 * valeur qui n'est pas un nombre est en anomalie, sa plage n'étant pas vérifiable.
 */
@Component
public class Val04ValueRangeEvaluator extends RowPredicateEvaluator {

    private static final String MIN = "min";
    private static final String MAX = "max";

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
                new ParamSchema.Param(MIN, ParamSchema.ParamType.DECIMAL, false, "rule.param.min"),
                new ParamSchema.Param(MAX, ParamSchema.ParamType.DECIMAL, false, "rule.param.max"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return presentValue(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var bounds = bounds(rule);
        String number = "TRY_CAST(" + rule.normalization().apply(data.quotedColumn(rule.firstColumn())) + " AS DOUBLE)";
        if (bounds.max() == null) {
            return SqlFragment.of(
                    number + " IS NULL OR " + number + " < ?", bounds.min().doubleValue());
        }
        if (bounds.min() == null) {
            return SqlFragment.of(
                    number + " IS NULL OR " + number + " > ?", bounds.max().doubleValue());
        }
        return SqlFragment.of(
                number + " IS NULL OR " + number + " < ? OR " + number + " > ?",
                bounds.min().doubleValue(),
                bounds.max().doubleValue());
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        var bounds = bounds(rule);
        String prefix = "La colonne « " + rule.firstColumn() + " » doit contenir une valeur numérique ";
        if (bounds.max() == null) {
            return prefix + "supérieure ou égale à " + plain(bounds.min()) + ".";
        }
        if (bounds.min() == null) {
            return prefix + "inférieure ou égale à " + plain(bounds.max()) + ".";
        }
        return prefix + "comprise entre " + plain(bounds.min()) + " et " + plain(bounds.max()) + ".";
    }

    private Bounds bounds(RuleDefinition rule) {
        BigDecimal min = RuleParams.optionalNumber(rule, MIN);
        BigDecimal max = RuleParams.optionalNumber(rule, MAX);
        if (min == null && max == null) {
            throw RuleParams.noneOf(rule, MIN, MAX);
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new RuleEvaluationException("La valeur minimale de la règle " + rule.ruleCode()
                    + " dépasse la valeur maximale (" + plain(min) + " > " + plain(max) + ")");
        }
        return new Bounds(min, max);
    }

    /** Borne affichée sans zéros inutiles ni notation scientifique (0.50 devient 0.5). */
    private static String plain(BigDecimal bound) {
        return bound.stripTrailingZeros().toPlainString();
    }

    private record Bounds(BigDecimal min, BigDecimal max) {}
}
