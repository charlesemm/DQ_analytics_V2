package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.ParamSchema;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * UNI-03 — Doublons après normalisation : « KOUAMÉ Jean » = « kouame jean ». Coche les options de
 * normalisation de la règle (espaces, majuscules, accents).
 */
@Component
public class Uni03NormalizedDuplicateEvaluator extends DuplicateGroupsEvaluator {

    @Override
    public String type() {
        return "UNI-03";
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(ParamSchema.ColumnArity.MANY, List.of());
    }

    @Override
    protected boolean normalizeKey() {
        return true;
    }
}
