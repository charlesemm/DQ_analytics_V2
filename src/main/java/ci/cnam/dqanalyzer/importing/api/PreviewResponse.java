package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportPreview;
import ci.cnam.dqanalyzer.importing.ImportPreview.PreviewRow;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import java.util.List;

/**
 * Aperçu renvoyé à l'écran d'import (IMP-04) : les lignes telles que les paramètres proposés les
 * donnent, et de quoi corriger ces paramètres — la liste des feuilles du classeur, et les paramètres
 * eux-mêmes.
 */
record PreviewResponse(
        ImportFormat format,
        ImportSettings settings,
        List<String> sheetNames,
        List<String> columns,
        List<PreviewRow> rows,
        boolean truncated) {

    static PreviewResponse of(ImportSettings settings, List<String> sheetNames, ImportPreview preview) {
        return new PreviewResponse(
                settings.format(), settings, sheetNames, preview.columns(), preview.rows(), preview.truncated());
    }
}
