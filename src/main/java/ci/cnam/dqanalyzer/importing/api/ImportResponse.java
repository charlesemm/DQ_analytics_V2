package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.ImportStatus;
import ci.cnam.dqanalyzer.importing.ReceivedFile;

/**
 * Réponse à l'envoi d'un fichier : ce qui a été conservé (IMP-06) et comment il va être lu.
 *
 * <p>Les paramètres retenus sont renvoyés tels quels, avec leur type : chaque format a les siens, et
 * l'écran d'import a besoin de les relire pour les proposer à la correction (IMP-02, IMP-03, IMP-08).
 */
record ImportResponse(
        long id,
        String originalName,
        long sizeBytes,
        String sha256,
        ImportFormat format,
        boolean hasHeader,
        ImportSettings settings,
        ImportStatus status) {

    static ImportResponse of(ReceivedFile received, ImportSettings retained) {
        return new ImportResponse(
                received.sourceFileId(),
                received.originalName(),
                received.sizeBytes(),
                received.sha256(),
                retained.format(),
                retained.hasHeader(),
                retained,
                ImportStatus.RUNNING);
    }
}
