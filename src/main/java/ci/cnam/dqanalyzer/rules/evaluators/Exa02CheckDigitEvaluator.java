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
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * EXA-02 — Clé de contrôle d'un identifiant.
 * <ul>
 *   <li>luhn : le dernier chiffre est la clé de Luhn (cartes, nombreux identifiants) ;</li>
 *   <li>mod97 : les 2 derniers chiffres valent 97 − (nombre qui précède modulo 97), comme le NIR français.</li>
 * </ul>
 * L'algorithme exact des identifiants CNAM / CNPS est à confirmer avec le métier (question ouverte du CDC).
 */
@Component
public class Exa02CheckDigitEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "EXA-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.ACCURACY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("algorithm", ParamType.TEXT, true, "param.algorithm"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        // Les espaces sont retirés avant le calcul : « 1 85 01 … » et « 18501… » sont le même identifiant
        String v = "replace(trim(CAST(" + data.quotedColumn(rule.firstColumn()) + " AS VARCHAR)), ' ', '')";
        String digitsOnly = "NOT regexp_full_match(" + v + ", '[0-9]{2,}')";
        return switch (RuleParams.of(rule).text("algorithm").toLowerCase(Locale.ROOT)) {
            case "luhn" ->
                SqlFragment.of(digitsOnly + " OR list_sum(list_transform(range(1, length(" + v + ") + 1),"
                        + " lambda i: CASE WHEN i % 2 = 0"
                        + " THEN (TRY_CAST(substr(reverse(" + v + "), i, 1) AS INTEGER) * 2) % 9"
                        + " + CASE WHEN substr(reverse(" + v + "), i, 1) = '9' THEN 9 ELSE 0 END"
                        + " ELSE TRY_CAST(substr(reverse(" + v + "), i, 1) AS INTEGER) END)) % 10 <> 0");
            case "mod97" ->
                SqlFragment.of(digitsOnly + " OR length(" + v + ") < 3 OR"
                        + " 97 - (TRY_CAST(left(" + v + ", length(" + v + ") - 2) AS HUGEINT) % 97)"
                        + " <> TRY_CAST(right(" + v + ", 2) AS INTEGER)");
            default ->
                throw new RuleEvaluationException("Règle " + rule.ruleCode() + " : algorithme inconnu (luhn ou mod97)");
        };
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La clé de contrôle de « " + rule.firstColumn() + " » est incorrecte.";
    }
}
