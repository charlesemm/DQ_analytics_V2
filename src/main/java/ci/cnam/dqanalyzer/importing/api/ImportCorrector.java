package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.DelimitedSettings;
import ci.cnam.dqanalyzer.importing.FixedWidthSettings;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.JsonSettings;
import ci.cnam.dqanalyzer.importing.ParquetSettings;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import ci.cnam.dqanalyzer.importing.XmlSettings;

/**
 * Applique les corrections de l'analyste aux paramètres détectés (IMP-02, IMP-03, IMP-08). Les choix
 * de l'analyste l'emportent toujours sur la détection automatique ; ce qu'il ne précise pas reste tel
 * que détecté.
 *
 * <p>Au même endroit pour les deux chemins d'arrivée d'un fichier : l'envoi direct et l'envoi par
 * morceaux, qui doivent comprendre les mêmes réglages.
 */
final class ImportCorrector {

    private ImportCorrector() {}

    static ImportSettings correct(ImportSettings detected, ImportCorrections corrections) {
        return switch (detected) {
            case DelimitedSettings delimited -> correctDelimited(delimited, corrections);
            case SheetSettings sheet -> correctSheet(sheet, corrections);
            case FixedWidthSettings fixedWidth -> correctFixedWidth(fixedWidth, corrections);
            case XmlSettings xml ->
                given(corrections.rowElement()) ? xml.withRowElement(corrections.rowElement()) : xml;
            case JsonSettings json ->
                given(corrections.arrayPath()) ? json.withArrayPath(corrections.arrayPath()) : json;
            // Un fichier Parquet porte son schéma : il n'y a rien à corriger.
            case ParquetSettings parquet -> parquet;
        };
    }

    private static DelimitedSettings correctDelimited(DelimitedSettings detected, ImportCorrections corrections) {
        var retained = detected;
        if (given(corrections.delimiter())) {
            retained = retained.withDelimiter(Delimiters.toCharacter(corrections.delimiter()));
        }
        if (given(corrections.charset())) {
            retained = retained.withCharsetName(corrections.charset());
        }
        if (corrections.hasHeader() != null) {
            retained = retained.withHasHeader(corrections.hasHeader());
        }
        return retained;
    }

    /** Pour un tableur, « pas d'en-tête » et « en-tête ligne n » sont le même réglage (IMP-02). */
    private static SheetSettings correctSheet(SheetSettings detected, ImportCorrections corrections) {
        var retained = detected;
        if (given(corrections.sheet())) {
            retained = retained.withSheetName(corrections.sheet());
        }
        if (corrections.headerRow() != null) {
            retained = retained.withHeaderRow(corrections.headerRow());
        }
        if (Boolean.FALSE.equals(corrections.hasHeader())) {
            retained = retained.withHeaderRow(SheetSettings.NO_HEADER);
        } else if (Boolean.TRUE.equals(corrections.hasHeader()) && !retained.hasHeader()) {
            retained = retained.withHeaderRow(1);
        }
        return retained;
    }

    private static FixedWidthSettings correctFixedWidth(FixedWidthSettings detected, ImportCorrections corrections) {
        var retained = detected;
        if (given(corrections.template())) {
            retained = retained.withFields(FixedWidthTemplates.parse(corrections.template()));
        }
        if (given(corrections.charset())) {
            retained = retained.withCharsetName(corrections.charset());
        }
        if (corrections.hasHeader() != null) {
            retained = retained.withHasHeader(corrections.hasHeader());
        }
        return retained;
    }

    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }
}
