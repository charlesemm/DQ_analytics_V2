package ci.cnam.dqanalyzer.testsupport;

import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.Severity;
import java.util.List;
import java.util.Map;

/** Fabrique de règles pour les tests. */
public final class Rules {

    private Rules() {}

    public static RuleDefinition rule(
            String type, Dimension dimension, List<String> columns, Map<String, Object> params) {
        return new RuleDefinition(
                1L,
                "R-TEST",
                1,
                "Règle de test " + type,
                type,
                dimension,
                columns,
                params,
                Normalization.NONE,
                Severity.MAJOR,
                95.0,
                null);
    }
}
