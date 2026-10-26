package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

/**
 * Contrôles d'admissibilité d'un fichier (IMP-05) : extension, taille, et surtout type réel du
 * contenu. L'extension ne prouve rien : un exécutable renommé « assures.csv » est refusé ici (SEC-03).
 */
public final class ImportValidation {

    /** Formats de ce premier incrément : texte délimité uniquement (CDC §5.1 bis). */
    public static final List<String> ACCEPTED_EXTENSIONS = List.of("csv", "tsv", "txt");

    private static final Set<String> ACCEPTED_MEDIA_TYPES =
            Set.of("text/csv", "text/plain", "text/tab-separated-values", "application/csv");

    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private final long maxSizeBytes;
    private final Detector detector;

    public ImportValidation(long maxSizeBytes) {
        this(maxSizeBytes, new DefaultDetector());
    }

    ImportValidation(long maxSizeBytes, Detector detector) {
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("La taille maximale doit être positive");
        }
        this.maxSizeBytes = maxSizeBytes;
        this.detector = Objects.requireNonNull(detector, "detector");
    }

    /**
     * @param file fichier déjà déposé sur le disque
     * @param originalName nom donné par l'utilisateur, utilisé pour l'extension et les messages
     * @throws ImportRejectedException si le fichier ne peut pas être importé
     */
    public void checkAcceptable(Path file, String originalName) {
        String fileName = fileName(originalName);
        checkExtension(fileName);
        checkSize(file);
        checkRealType(file, fileName);
    }

    private void checkExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ACCEPTED_EXTENSIONS.contains(extension)) {
            throw new ImportRejectedException("import.error.extension-refusee", fileName);
        }
    }

    private void checkSize(Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
        if (size == 0) {
            throw new ImportRejectedException("import.error.fichier-vide");
        }
        if (size > maxSizeBytes) {
            throw new ImportRejectedException(
                    "import.error.fichier-trop-grand", Math.max(1L, maxSizeBytes / BYTES_PER_MEGABYTE));
        }
    }

    /** Type réel déduit du contenu (Tika), le nom du fichier ne servant que d'indice. */
    private void checkRealType(Path file, String fileName) {
        var metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try (var stream = TikaInputStream.get(file)) {
            var mediaType = detector.detect(stream, metadata);
            String baseType = mediaType.getBaseType().toString();
            if (!ACCEPTED_MEDIA_TYPES.contains(baseType) && !"text".equals(mediaType.getType())) {
                throw new ImportRejectedException("import.error.type-inattendu");
            }
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    /** Certains navigateurs envoient un chemin complet : on ne garde que le nom. */
    private static String fileName(String originalName) {
        String name = originalName == null ? "" : originalName.trim();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return separator < 0 ? name : name.substring(separator + 1);
    }
}
