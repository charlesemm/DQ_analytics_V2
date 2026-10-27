package ci.cnam.dqanalyzer.importing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.EnumSet;
import java.util.Set;

/**
 * Paramètres de lecture d'un tableur : feuille à lire et ligne d'en-tête (IMP-02).
 *
 * @param format format du classeur, parmi {@link #SHEET_FORMATS}
 * @param sheetName nom de la feuille ; {@code null} ou vide = la première feuille du classeur
 * @param headerRow numéro de la ligne d'en-tête dans la feuille, à partir de 1 ;
 *     {@value #NO_HEADER} si la feuille n'a pas d'en-tête et commence directement par les données
 */
public record SheetSettings(ImportFormat format, String sheetName, int headerRow) implements ImportSettings {

    /** Feuille sans ligne d'en-tête : les colonnes seront nommées « colonne_1 », « colonne_2 »… */
    public static final int NO_HEADER = 0;

    /** Formats qui se lisent feuille par feuille. */
    public static final Set<ImportFormat> SHEET_FORMATS =
            EnumSet.of(ImportFormat.XLSX, ImportFormat.XLS, ImportFormat.ODS);

    public SheetSettings {
        if (!SHEET_FORMATS.contains(format)) {
            throw new ImportRejectedException("import.error.format-sans-feuille");
        }
        if (headerRow < NO_HEADER) {
            throw new ImportRejectedException("import.error.ligne-entete-invalide");
        }
        sheetName = sheetName == null || sheetName.isBlank() ? null : sheetName.trim();
    }

    /** Première feuille, en-tête sur la première ligne : le cas de très loin le plus fréquent. */
    public static SheetSettings defaults(ImportFormat format) {
        return new SheetSettings(format, null, 1);
    }

    @JsonIgnore
    @Override
    public boolean hasHeader() {
        return headerRow > NO_HEADER;
    }

    public SheetSettings withSheetName(String newSheetName) {
        return new SheetSettings(format, newSheetName, headerRow);
    }

    public SheetSettings withHeaderRow(int newHeaderRow) {
        return new SheetSettings(format, sheetName, newHeaderRow);
    }
}
