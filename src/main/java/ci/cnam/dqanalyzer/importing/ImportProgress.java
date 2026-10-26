package ci.cnam.dqanalyzer.importing;

/**
 * Où en est le chargement d'un fichier.
 *
 * @param sourceFileId fichier concerné
 * @param status état du chargement
 * @param percent avancement de 0 à 100
 * @param rowCount lignes chargées ; 0 tant que le chargement n'est pas terminé
 * @param raggedRowCount lignes dont le nombre de champs ne correspondait pas à l'en-tête
 * @param message explication en français si le chargement a échoué, chaîne vide sinon
 */
public record ImportProgress(
        long sourceFileId, ImportStatus status, int percent, long rowCount, long raggedRowCount, String message) {}
