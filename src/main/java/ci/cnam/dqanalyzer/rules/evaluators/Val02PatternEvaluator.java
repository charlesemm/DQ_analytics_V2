package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * VAL-02 — Format par expression régulière, ou par motif prédéfini (CDC §6). La valeur doit correspondre
 * au motif en entier, pas seulement en partie. Syntaxe des expressions : RE2 (celle de DuckDB).
 */
@Component
public class Val02PatternEvaluator extends FilledValueEvaluator {

    /** Motifs prédéfinis. Les formats des identifiants CNAM et CNPS seront ajoutés quand le métier les aura fournis. */
    static final Map<String, String> PRESETS = Map.of(
            "email", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
            "telephone_ci", "(\\+225|00225)?(01|05|07|21|25|27)[0-9]{8}",
            "date_jjmmaaaa", "(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[0-2])/[0-9]{4}",
            "code_postal", "[0-9]{2,5}",
            "alphanum_majuscule", "[A-Z0-9]+");

    @Override
    public String type() {
        return "VAL-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("preset", ParamType.TEXT, false, "param.preset"),
                new Param("regex", ParamType.TEXT, false, "param.regex"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String pattern = params.optionalText("preset")
                .map(name -> {
                    String preset = PRESETS.get(name);
                    if (preset == null) {
                        throw new RuleEvaluationException("Règle " + rule.ruleCode() + " : motif inconnu « " + name
                                + " » (motifs : " + String.join(", ", PRESETS.keySet()) + ")");
                    }
                    return preset;
                })
                .orElseGet(() -> params.text("regex"));
        return SqlFragment.of("NOT regexp_full_match(" + normalizedValue(data, rule) + ", ?)", pattern);
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » ne respecte pas le format attendu.";
    }
}
