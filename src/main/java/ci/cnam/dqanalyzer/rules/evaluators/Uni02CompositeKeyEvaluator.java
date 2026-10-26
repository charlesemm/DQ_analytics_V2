package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * UNI-02 — Clé composée unique : nom + prénoms + date de naissance n'apparaissent qu'une fois. Deux
 * lignes sont doublons seulement si toutes les colonnes de la clé sont identiques. Une ligne dont une
 * seule colonne de la clé est vide n'est pas contrôlée : sa clé est incomplète.
 */
@Component
public class Uni02CompositeKeyEvaluator extends DuplicateGroupEvaluator {

    @Override
    public String type() {
        return "UNI-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.UNIQUENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.manyColumns();
    }

    @Override
    protected List<String> keyColumns(RuleDefinition rule) {
        var columns = rule.targetColumns();
        if (columns.size() < 2) {
            throw new RuleEvaluationException("La règle " + rule.ruleCode()
                    + " doit cibler au moins deux colonnes ; pour une seule colonne, utiliser UNI-01");
        }
        return columns;
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La combinaison « " + String.join(" + ", rule.targetColumns())
                + " » doit être unique : ces valeurs apparaissent plusieurs fois dans le fichier.";
    }
}
