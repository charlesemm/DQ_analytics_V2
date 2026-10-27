package ci.cnam.dqanalyzer.importing;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Formats de fichiers acceptés (CDC §5.1 bis). Chaque valeur connaît ses extensions et les types de
 * contenu réels que Tika doit reconnaître : l'extension seule ne prouve rien, un exécutable renommé
 * « assures.xlsx » est refusé (SEC-03).
 *
 * <p>Un format n'entre dans cette liste que lorsque son lecteur existe : ce que l'outil accepte à
 * l'envoi est exactement ce qu'il sait charger.
 */
public enum ImportFormat {

    /** .csv, .tsv, .txt : séparateur, encodage et guillemets paramétrables (IMP-03). */
    DELIMITED(
            List.of("csv", "tsv", "txt"),
            Set.of("text/csv", "text/plain", "text/tab-separated-values", "application/csv")),

    /** Excel 2007 et suivants : feuille et ligne d'en-tête paramétrables (IMP-02). */
    XLSX(
            List.of("xlsx", "xlsm"),
            Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel.sheet.macroEnabled.12",
                    "application/x-tika-ooxml"));

    private final List<String> extensions;
    private final Set<String> mediaTypes;

    ImportFormat(List<String> extensions, Set<String> mediaTypes) {
        this.extensions = extensions;
        this.mediaTypes = mediaTypes;
    }

    /** Format déduit de l'extension du nom de fichier ; vide si l'extension n'est pas acceptée. */
    public static Optional<ImportFormat> ofFileName(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return List.of(values()).stream()
                .filter(format -> format.extensions.contains(extension))
                .findFirst();
    }

    /** Toutes les extensions acceptées, pour le message de refus et le sélecteur de fichier. */
    public static String acceptedExtensions() {
        return List.of(values()).stream()
                .flatMap(format -> format.extensions.stream())
                .distinct()
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /** Vrai si ce type de contenu, déduit des premiers octets du fichier, convient à ce format. */
    public boolean accepts(String mediaType) {
        return mediaTypes.contains(mediaType);
    }

    public List<String> extensions() {
        return extensions;
    }
}
