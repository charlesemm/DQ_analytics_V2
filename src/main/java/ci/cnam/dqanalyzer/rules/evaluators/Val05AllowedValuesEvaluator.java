package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * VAL-05 — Liste de valeurs autorisées ({@code values}). La valeur du fichier et les valeurs
 * autorisées subissent la même normalisation, ce qui permet d'ignorer la casse ou les accents
 * (« m » = « M »). Seules les valeurs renseignées sont contrôlées : une valeur absente relève de CMP-01.
 */
@Component
public class Val05AllowedValuesEvaluator extends RowPredicateEvaluator {

    private static final String VALUES = "values";

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
        return ParamSchema.singleColumn(
                new ParamSchema.Param(VALUES, ParamSchema.ParamType.TEXT_LIST, true, "rule.param.values"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return presentValue(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var allowed = RuleParams.requiredTextList(rule, VALUES);
        String value = rule.normalization().apply(data.quotedColumn(rule.firstColumn()));
        String placeholders = Stream.generate(() -> rule.normalization().apply("?"))
                .limit(allowed.size())
                .collect(Collectors.joining(", "));
        return SqlFragment.of(value + " NOT IN (" + placeholders + ")", allowed.toArray());
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La colonne « " + rule.firstColumn() + " » doit contenir une des valeurs autorisées : "
                + String.join(", ", RuleParams.requiredTextList(rule, VALUES)) + ".";
    }
}
