package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.ImportStatus;
import ci.cnam.dqanalyzer.importing.ReceivedFile;

/** Réponse à l'envoi d'un fichier : ce qui a été conservé (IMP-06) et comment il va être lu. */
record ImportResponse(
        long id,
        String originalName,
        long sizeBytes,
        String sha256,
        String delimiter,
        String charsetName,
        boolean hasHeader,
        ImportStatus status) {

    static ImportResponse of(ReceivedFile received, ImportSettings retained) {
        return new ImportResponse(
                received.sourceFileId(),
                received.originalName(),
                received.sizeBytes(),
                received.sha256(),
                Delimiters.toName(retained.delimiter()),
                retained.charsetName(),
                retained.hasHeader(),
                ImportStatus.RUNNING);
    }
}
