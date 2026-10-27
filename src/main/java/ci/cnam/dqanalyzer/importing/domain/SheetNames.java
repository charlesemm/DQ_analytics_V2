package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import java.nio.file.Path;
import java.util.List;

/**
 * Noms des feuilles d'un classeur, quel que soit son format : l'écran d'import en a besoin pour
 * proposer le choix à l'analyste (IMP-02), avant toute lecture des données.
 */
public final class SheetNames {

    private SheetNames() {}

    /** Liste vide pour un format qui n'est pas un tableur : il n'y a alors rien à choisir. */
    public static List<String> of(Path file, ImportFormat format) {
        return switch (format) {
            case XLSX -> XlsxRowSource.sheetNames(file);
            case XLS -> XlsRowSource.sheetNames(file);
            case ODS -> OdsRowSource.sheetNames(file);
            default -> List.of();
        };
    }
}
