package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.DelimitedSettings;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import java.nio.file.Path;

/**
 * Choisit le lecteur qui convient aux paramètres retenus. Seul endroit du module où le format d'un
 * fichier décide de quelque chose : tout ce qui vient après lit des lignes, sans savoir d'où elles
 * sortent (CDC §5.1 bis).
 */
public final class RowSources {

    private RowSources() {}

    /**
     * Ouvre le fichier dans son format. À refermer par l'appelant.
     *
     * @throws ci.cnam.dqanalyzer.importing.ImportRejectedException si le fichier est illisible
     */
    public static RowSource open(Path file, ImportSettings settings) {
        return switch (settings) {
            case DelimitedSettings delimited -> DelimitedRowSource.open(file, delimited);
            case SheetSettings sheet ->
                switch (sheet.format()) {
                    case XLSX -> XlsxRowSource.open(file, sheet);
                    default -> throw new IllegalStateException("Format de tableur sans lecteur : " + sheet.format());
                };
        };
    }
}
