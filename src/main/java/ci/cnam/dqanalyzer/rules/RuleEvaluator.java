package ci.cnam.dqanalyzer.rules;

/**
 * Un type de règle du catalogue (CDC §6). Ajouter un type = ajouter une classe {@code @Component}
 * qui implémente cette interface, sans toucher au moteur (MAI-01).
 */
public interface RuleEvaluator {

    /** Code du catalogue, par ex. "CMP-01". */
    String type();

    Dimension dimension();

    ParamSchema paramSchema();

    /**
     * Évalue la règle sur le jeu de données. Les anomalies sont envoyées au fil de l'eau dans le sink ;
     * le résultat ne contient que des comptages.
     */
    RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink);
}
