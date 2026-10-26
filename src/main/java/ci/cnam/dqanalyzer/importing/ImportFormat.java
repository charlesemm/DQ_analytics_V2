package ci.cnam.dqanalyzer.importing;

/**
 * Formats de fichiers acceptés (CDC §5.1 bis). Seul le texte délimité est lu pour l'instant ; les
 * autres valeurs viendront avec leurs lecteurs respectifs.
 */
public enum ImportFormat {
    /** .csv, .tsv, .txt : séparateur, encodage et guillemets paramétrables (IMP-03). */
    DELIMITED
}
