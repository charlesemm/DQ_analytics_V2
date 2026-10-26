package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.ParamSchema;
import org.springframework.stereotype.Component;

/** UNI-01 — Valeur unique sur une colonne : « un n° d'immatriculation n'apparaît qu'une fois ». */
@Component
public class Uni01UniqueValueEvaluator extends DuplicateGroupsEvaluator {

    @Override
    public String type() {
        return "UNI-01";
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn();
    }

    @Override
    protected boolean normalizeKey() {
        return false;
    }
}
