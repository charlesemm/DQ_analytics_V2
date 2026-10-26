package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Catalogue des types de règles disponibles, découverts automatiquement par Spring. */
@Component
public class RuleEvaluatorRegistry {

    private final Map<String, RuleEvaluator> evaluators;

    public RuleEvaluatorRegistry(List<RuleEvaluator> evaluators) {
        this.evaluators = evaluators.stream()
                .collect(Collectors.toUnmodifiableMap(RuleEvaluator::type, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Deux évaluateurs déclarent le type " + a.type() + " : "
                            + a.getClass().getName() + " et " + b.getClass().getName());
                }));
    }

    public RuleEvaluator get(String type) {
        var evaluator = evaluators.get(type);
        if (evaluator == null) {
            throw new RuleEvaluationException("Type de règle inconnu : " + type);
        }
        return evaluator;
    }

    public SortedSet<String> types() {
        return new TreeSet<>(evaluators.keySet());
    }
}
