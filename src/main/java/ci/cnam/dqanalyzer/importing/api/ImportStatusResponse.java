package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ImportProgress;
import ci.cnam.dqanalyzer.importing.ImportStatus;

/** Avancement d'un import, interrogé pendant le chargement. */
record ImportStatusResponse(
        long id, ImportStatus status, int percent, long rowCount, long raggedRowCount, String message) {

    static ImportStatusResponse of(ImportProgress progress) {
        return new ImportStatusResponse(
                progress.sourceFileId(),
                progress.status(),
                progress.percent(),
                progress.rowCount(),
                progress.raggedRowCount(),
                progress.message());
    }
}
