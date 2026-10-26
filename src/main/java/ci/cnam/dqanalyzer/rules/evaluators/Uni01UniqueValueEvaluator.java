package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * UNI-01 — Valeur unique sur une colonne : un n° d'immatriculation n'apparaît qu'une fois. Règle sans
 * paramètre. La comparaison se fait après normalisation (option de la règle) : cocher « majuscules »
 * rend « a100 » et « A100 » doublons. Les valeurs absentes ou blanches ne sont pas contrôlées.
 */
@Component
public class Uni01UniqueValueEvaluator extends DuplicateGroupEvaluator {

    @Override
    public String type() {
        return "UNI-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.UNIQUENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn();
    }

    @Override
    protected List<String> keyColumns(RuleDefinition rule) {
        return List.of(rule.firstColumn());
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La colonne « " + rule.firstColumn()
                + " » doit contenir une valeur unique : cette valeur apparaît plusieurs fois dans le fichier.";
    }
}
