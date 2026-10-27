package ci.cnam.dqanalyzer.importing;

import java.io.InputStream;
import java.util.List;

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
}
