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
                    "application/x-tika-ooxml")),

    /**
     * Excel 97-2003 : limité à 100 Mo (CDC §5.1 bis), parce qu'il se lit en mémoire et qu'une feuille
     * n'y dépasse de toute façon pas 65 536 lignes.
     */
    XLS(
            List.of("xls"),
            Set.of("application/vnd.ms-excel", "application/x-tika-msoffice"),
            ImportFormat.HUNDRED_MEGABYTES),

    /** OpenDocument : limité à 100 Mo comme le CDC le prévoit. */
    ODS(
            List.of("ods"),
            Set.of(
                    "application/vnd.oasis.opendocument.spreadsheet",
                    "application/x-vnd.oasis.opendocument.spreadsheet"),
            ImportFormat.HUNDRED_MEGABYTES),

    /**
     * Parquet : aucun paramètre, le fichier porte son schéma. Il se reconnaît à ses quatre premiers
     * octets, « PAR1 », et non à son type de contenu, que Tika ne distingue pas d'un binaire
     * quelconque.
     */
    PARQUET(List.of("parquet"), Set.of("application/vnd.apache.parquet", "application/x-parquet"));

    /** Marque de début (et de fin) d'un fichier Parquet. */
    public static final String PARQUET_MAGIC = "PAR1";

    /** Limite du CDC pour les formats qui se lisent en mémoire (§5.1 bis). */
    private static final long HUNDRED_MEGABYTES = 100L * 1024 * 1024;

    private final List<String> extensions;
    private final Set<String> mediaTypes;

    /** Taille maximale propre au format ; sans limite propre, c'est celle de la configuration. */
    private final long maxSizeBytes;

    ImportFormat(List<String> extensions, Set<String> mediaTypes) {
        this(extensions, mediaTypes, Long.MAX_VALUE);
    }

    ImportFormat(List<String> extensions, Set<String> mediaTypes, long maxSizeBytes) {
        this.extensions = extensions;
        this.mediaTypes = mediaTypes;
        this.maxSizeBytes = maxSizeBytes;
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

    /**
     * Taille maximale propre à ce format, ou {@link Long#MAX_VALUE} s'il n'en a pas : c'est alors
     * celle de la configuration qui s'applique.
     */
    public long maxSizeBytes() {
        return maxSizeBytes;
    }
}
