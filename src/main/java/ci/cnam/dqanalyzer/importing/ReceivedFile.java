package ci.cnam.dqanalyzer.importing;

/**
 * Fichier reçu, conservé et contrôlé, pas encore chargé (IMP-06).
 *
 * @param sourceFileId identifiant du fichier source en base
 * @param originalName nom donné par l'utilisateur
 * @param sizeBytes taille réellement reçue
 * @param sha256 empreinte du contenu, en minuscules
 * @param detectedSettings paramètres de lecture proposés (IMP-07), que l'analyste peut corriger
 */
public record ReceivedFile(
        long sourceFileId, String originalName, long sizeBytes, String sha256, ImportSettings detectedSettings) {}
