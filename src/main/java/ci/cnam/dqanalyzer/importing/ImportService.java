package ci.cnam.dqanalyzer.importing;

import java.io.InputStream;

/**
 * Point d'entrée du module : recevoir un fichier, puis le charger en tâche de fond.
 *
 * <p>Les deux temps sont séparés exprès. La réception enregistre et contrôle le fichier et propose
 * des paramètres de lecture (IMP-07) ; le chargement n'est lancé qu'avec les paramètres retenus, que
 * l'analyste a pu corriger (IMP-03). C'est aussi là que viendra se greffer l'aperçu des 50 premières
 * lignes (IMP-04).
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
     * Lance la lecture du fichier vers sa table DuckDB et rend la main immédiatement : l'avancement se
     * suit avec {@link #progressOf(long)}.
     */
    void startLoad(long sourceFileId, ImportSettings settings);

    /** @throws java.util.NoSuchElementException si l'import n'existe pas */
    ImportProgress progressOf(long sourceFileId);
}
