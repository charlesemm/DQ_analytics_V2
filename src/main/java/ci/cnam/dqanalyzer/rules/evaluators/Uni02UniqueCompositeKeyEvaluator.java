package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.ParamSchema;
import java.util.List;
import org.springframework.stereotype.Component;

/** UNI-02 — Clé composée unique : « nom + prénoms + date de naissance uniques ». */
@Component
public class Uni02UniqueCompositeKeyEvaluator extends DuplicateGroupsEvaluator {

    @Override
    public String type() {
        return "UNI-02";
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(ParamSchema.ColumnArity.MANY, List.of());
    }

    @Override
    protected boolean normalizeKey() {
        return false;
    }
}
