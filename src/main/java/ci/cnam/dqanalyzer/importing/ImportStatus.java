package ci.cnam.dqanalyzer.importing;

/** Avancement du chargement d'un fichier vers sa table DuckDB. */
public enum ImportStatus {
    /** Fichier reçu et contrôlé, chargement pas encore lancé. */
    PENDING,
    /** Lecture en flux en cours. */
    RUNNING,
    /** Table prête à être analysée. */
    DONE,
    /** Chargement abandonné ; la raison est lisible par l'analyste. */
    FAILED
}
