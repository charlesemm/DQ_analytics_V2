package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Map;
import org.springframework.stereotype.Component;

/** VAL-07 — Caractères interdits : « pas de chiffres dans un nom ». */
@Component
public class Val07ForbiddenCharactersEvaluator extends FilledValueEvaluator {

    /** Raccourcis pour les cas fréquents ; sinon l'analyste saisit les caractères eux-mêmes. */
    private static final Map<String, String> PRESETS =
            Map.of("chiffres", "[0-9]", "speciaux", "[@#$%&*!?<>{}\\[\\]|\\\\^~]");

    @Override
    public String type() {
        return "VAL-07";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("characters", ParamType.TEXT, true, "param.forbiddenCharacters"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        String characters = RuleParams.of(rule).text("characters");
        String pattern = PRESETS.getOrDefault(characters, characterClass(characters));
        return SqlFragment.of(
                "regexp_matches(CAST(" + data.quotedColumn(rule.firstColumn()) + " AS VARCHAR), ?)", pattern);
    }

    /** « 0123 » → « [0123] », en protégeant les caractères qui ont un sens dans une classe RE2. */
    static String characterClass(String characters) {
        var sb = new StringBuilder("[");
        characters.codePoints().forEach(cp -> {
            if ("\\]^-[".indexOf(cp) >= 0) {
                sb.append('\\');
            }
            sb.appendCodePoint(cp);
        });
        return sb.append(']').toString();
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » contient un caractère interdit.";
    }
}
