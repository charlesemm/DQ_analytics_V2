package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/**
 * VAL-03 — Longueur d'une valeur, entre {@code minLength} et {@code maxLength} caractères (au moins
 * une des deux bornes). La longueur est mesurée après normalisation, sur les seules valeurs
 * renseignées : une valeur absente relève de CMP-01.
 */
@Component
public class Val03LengthEvaluator extends RowPredicateEvaluator {

    private static final String MIN = "minLength";
    private static final String MAX = "maxLength";

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
                new ParamSchema.Param(MIN, ParamSchema.ParamType.INTEGER, false, "rule.param.minLength"),
                new ParamSchema.Param(MAX, ParamSchema.ParamType.INTEGER, false, "rule.param.maxLength"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return presentValue(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var bounds = bounds(rule);
        String length = "length(" + rule.normalization().apply(data.quotedColumn(rule.firstColumn())) + ")";
        if (bounds.max() == null) {
            return SqlFragment.of(length + " < ?", bounds.min());
        }
        if (bounds.min() == null) {
            return SqlFragment.of(length + " > ?", bounds.max());
        }
        return SqlFragment.of(length + " < ? OR " + length + " > ?", bounds.min(), bounds.max());
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        var bounds = bounds(rule);
        String prefix = "La colonne « " + rule.firstColumn() + " » doit contenir ";
        if (bounds.max() == null) {
            return prefix + "au moins " + bounds.min() + " caractères.";
        }
        if (bounds.min() == null) {
            return prefix + "au plus " + bounds.max() + " caractères.";
        }
        if (bounds.min().equals(bounds.max())) {
            return prefix + "exactement " + bounds.min() + " caractères.";
        }
        return prefix + "entre " + bounds.min() + " et " + bounds.max() + " caractères.";
    }

    private Bounds bounds(RuleDefinition rule) {
        Integer min = RuleParams.optionalInteger(rule, MIN);
        Integer max = RuleParams.optionalInteger(rule, MAX);
        if (min == null && max == null) {
            throw RuleParams.noneOf(rule, MIN, MAX);
        }
        if ((min != null && min < 0) || (max != null && max < 0)) {
            throw new RuleEvaluationException(
                    "Les longueurs de la règle " + rule.ruleCode() + " ne peuvent pas être négatives");
        }
        if (min != null && max != null && min > max) {
            throw new RuleEvaluationException("La longueur minimale de la règle " + rule.ruleCode()
                    + " dépasse la longueur maximale (" + min + " > " + max + ")");
        }
        return new Bounds(min, max);
    }

    private record Bounds(Integer min, Integer max) {}
}
