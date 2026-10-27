package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.DelimitedSettings;
import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.ImportStatus;
import ci.cnam.dqanalyzer.importing.ParquetSettings;
import ci.cnam.dqanalyzer.importing.ReceivedFile;
import ci.cnam.dqanalyzer.importing.SheetSettings;

/**
 * Réponse à l'envoi d'un fichier : ce qui a été conservé (IMP-06) et comment il va être lu. Les
 * champs qui ne concernent pas le format reconnu restent absents de la réponse.
 */
record ImportResponse(
        long id,
        String originalName,
        long sizeBytes,
        String sha256,
        ImportFormat format,
        String delimiter,
        String charsetName,
        String sheetName,
        Integer headerRow,
        boolean hasHeader,
        ImportStatus status) {

    static ImportResponse of(ReceivedFile received, ImportSettings retained) {
        return switch (retained) {
            case DelimitedSettings delimited ->
                new ImportResponse(
                        received.sourceFileId(),
                        received.originalName(),
                        received.sizeBytes(),
                        received.sha256(),
                        delimited.format(),
                        Delimiters.toName(delimited.delimiter()),
                        delimited.charsetName(),
                        null,
                        null,
                        delimited.hasHeader(),
                        ImportStatus.RUNNING);
            case SheetSettings sheet ->
                new ImportResponse(
                        received.sourceFileId(),
                        received.originalName(),
                        received.sizeBytes(),
                        received.sha256(),
                        sheet.format(),
                        null,
                        null,
                        sheet.sheetName(),
                        sheet.headerRow(),
                        sheet.hasHeader(),
                        ImportStatus.RUNNING);
            case ParquetSettings parquet ->
                new ImportResponse(
                        received.sourceFileId(),
                        received.originalName(),
                        received.sizeBytes(),
                        received.sha256(),
                        parquet.format(),
                        null,
                        null,
                        null,
                        null,
                        parquet.hasHeader(),
                        ImportStatus.RUNNING);
        };
    }
}
