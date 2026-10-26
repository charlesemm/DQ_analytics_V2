package ci.cnam.dqanalyzer.rules;

/**
 * Donne accès aux tables de référence (EXA-01, INT-01). Implémenté plus tard par le module reference,
 * qui les charge dans la même base DuckDB que le fichier analysé.
 */
public interface ReferenceProvider {

    /** Nom de la table DuckDB contenant la version demandée de la table de référence. */
    String duckDbTable(long referenceTableId);

    ReferenceProvider NONE = id -> {
        throw new RuleEvaluationException("Aucune table de référence n'est disponible pour cette analyse");
    };
}
