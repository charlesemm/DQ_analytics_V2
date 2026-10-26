package ci.cnam.dqanalyzer.importing;

/**
 * Reçoit l'avancement d'un chargement au fil de la lecture : le contrôleur a rendu la main
 * immédiatement, c'est par là que l'écran apprend où en est l'import.
 */
@FunctionalInterface
public interface ImportProgressListener {

    /**
     * @param percent avancement estimé, de 0 à 99 ; 100 n'est posé qu'à la fin du chargement
     * @param rowsLoaded nombre de lignes déjà écrites dans la table
     */
    void progress(int percent, long rowsLoaded);

    /** Pour les appels qui ne suivent pas l'avancement (tests, imports très courts). */
    ImportProgressListener IGNORE = (percent, rowsLoaded) -> {};
}
