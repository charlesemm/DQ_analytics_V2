package ci.cnam.dqanalyzer.importing;

import java.io.InputStream;
import java.util.List;
import java.util.function.Function;

/**
 * Point d'entrée du module : recevoir un fichier, le montrer, puis le charger en tâche de fond.
 *
 * <p>Les trois temps sont séparés exprès. La réception enregistre et contrôle le fichier et propose
 * des paramètres de lecture (IMP-07) ; l'aperçu montre ce que ces paramètres donneraient (IMP-04) ;
 * le chargement n'est lancé qu'avec les paramètres retenus, que l'analyste a pu corriger (IMP-02,
 * IMP-03, IMP-08).
 */
public interface ImportService {

    /**
     * Écrit le fichier sur le disque en flux, calcule son empreinte, le refuse s'il est inexploitable
     * (IMP-05) et détecte ses paramètres de lecture (IMP-07).
     *
     * @param content flux du fichier reçu ; il est lu une seule fois et n'est pas fermé ici
     * @param originalName nom donné par l'utilisateur, conservé tel quel (IMP-06)
     * @throws ImportRejectedException si le fichier ne peut pas être importé
     */
    ReceivedFile receive(InputStream content, String originalName);

    /**
     * Paramètres de lecture enregistrés avec le fichier : ceux détectés à la réception, ou ceux
     * retenus si le chargement a déjà été lancé.
     *
     * @throws java.util.NoSuchElementException si l'import n'existe pas
     */
    ImportSettings settingsOf(long sourceFileId);

    /**
     * Noms des feuilles du classeur, pour que l'analyste choisisse la sienne (IMP-02). Liste vide
     * pour les formats qui ne sont pas des tableurs.
     *
     * @throws java.util.NoSuchElementException si l'import n'existe pas
     */
    List<String> sheetNamesOf(long sourceFileId);

    /**
     * Lit les {@value ImportPreview#ROWS} premières lignes avec les paramètres proposés, sans rien
     * charger ni enregistrer (IMP-04) : l'analyste voit le résultat avant de valider.
     *
     * @throws java.util.NoSuchElementException si l'import n'existe pas
     * @throws ImportRejectedException si le fichier ne peut pas être lu avec ces paramètres
     */
    ImportPreview preview(long sourceFileId, ImportSettings settings);

    /**
     * Lance la lecture du fichier vers sa table DuckDB et rend la main immédiatement : l'avancement se
     * suit avec {@link #progressOf(long)}.
     */
    void startLoad(long sourceFileId, ImportSettings settings);

    /** @throws java.util.NoSuchElementException si l'import n'existe pas */
    ImportProgress progressOf(long sourceFileId);

    /**
     * Table DuckDB d'un fichier déjà chargé, pour les modules qui l'analysent (profilage, exécution).
     *
     * <p>Le chargement est asynchrone (IMP-06) : tant qu'il n'est pas terminé, la table est vide ou
     * n'existe pas encore. Un écran ouvert trois secondes après le dépôt d'un fichier de 2 Go doit
     * afficher « chargement en cours », pas « 0 ligne » : d'où le refus plutôt qu'une table vide.
     *
     * @throws java.util.NoSuchElementException si l'import n'existe pas
     * @throws ImportRejectedException si son chargement n'est pas terminé
     */
    ImportedTable tableOf(long sourceFileId);

    /**
     * Fichiers dont le chargement est terminé, du plus récent au plus ancien, pour les écrans qui
     * font choisir un fichier (REG-03, MOD-02, lancement d'une analyse).
     *
     * @param limit nombre maximal de fichiers renvoyés
     */
    List<LoadedFile> loadedFiles(int limit);

    /**
     * Lit un fichier dans une table DuckDB jetable et le confie au lecteur, sans l'enregistrer comme
     * fichier importé : pour les modules qui ont besoin du contenu d'un fichier sans en faire une
     * analyse, comme le chargement d'une table de référence (REF-01).
     *
     * <p>Le format, l'encodage et le séparateur sont détectés comme pour un import (IMP-07), et les
     * mêmes refus s'appliquent (IMP-05). Le fichier reçu et la base jetable sont supprimés à la
     * sortie, que le lecteur réussisse ou non : rien ne reste sur le disque.
     *
     * @param reader reçoit la table chargée ; il doit en avoir fini à son retour
     * @throws ImportRejectedException si le fichier ne peut pas être lu
     */
    <T> T readAsTable(InputStream content, String originalName, Function<ImportedTable, T> reader);
}
