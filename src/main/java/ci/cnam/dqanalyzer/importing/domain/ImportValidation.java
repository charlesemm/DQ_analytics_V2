package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

/**
 * Contrôles d'admissibilité d'un fichier (IMP-05) : extension, taille, et surtout type réel du
 * contenu. L'extension ne prouve rien : un exécutable renommé « assures.csv » est refusé ici (SEC-03).
 *
 * <p>Les formats acceptés sont ceux de {@link ImportFormat}, c'est-à-dire ceux dont le lecteur
 * existe : l'outil n'accepte jamais un fichier qu'il ne saurait pas charger ensuite.
 */
public final class ImportValidation {

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
     * @return le format reconnu, celui dans lequel le fichier sera lu (IMP-07)
     * @throws ImportRejectedException si le fichier ne peut pas être importé
     */
    public ImportFormat checkAcceptable(Path file, String originalName) {
        String fileName = fileName(originalName);
        var format = checkExtension(fileName);
        checkSize(file, format);
        checkRealType(file, fileName, format);
        return format;
    }

    /**
     * Contrôle que le nom seul permet d'espérer un format lisible. À appeler avant d'écrire quoi que
     * ce soit sur le disque : inutile de recopier 2 Go pour refuser un .pdf à la fin.
     *
     * @return le format attendu d'après l'extension, à confirmer par {@link #checkAcceptable}
     */
    public ImportFormat checkName(String originalName) {
        return checkExtension(fileName(originalName));
    }

    private ImportFormat checkExtension(String fileName) {
        return ImportFormat.ofFileName(fileName)
                .orElseThrow(() -> new ImportRejectedException(
                        "import.error.extension-refusee", fileName, ImportFormat.acceptedExtensions()));
    }

    /**
     * La limite retenue est la plus basse des deux : celle de la configuration et celle du format.
     * Un .xls ou un .ods se lit en mémoire, d'où une limite propre bien plus basse (CDC §5.1 bis).
     */
    private void checkSize(Path file, ImportFormat format) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
        if (size == 0) {
            throw new ImportRejectedException("import.error.fichier-vide");
        }
        long limit = Math.min(maxSizeBytes, format.maxSizeBytes());
        if (size > limit) {
            throw new ImportRejectedException(
                    "import.error.fichier-trop-grand", Math.max(1L, limit / BYTES_PER_MEGABYTE));
        }
    }

    /** Type réel déduit du contenu (Tika), le nom du fichier ne servant que d'indice. */
    private void checkRealType(Path file, String fileName, ImportFormat format) {
        if (format == ImportFormat.PARQUET) {
            checkParquetMagic(file);
            return;
        }
        var metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try (var stream = TikaInputStream.get(file)) {
            var mediaType = detector.detect(stream, metadata);
            String baseType = mediaType.getBaseType().toString();
            // Un fichier texte délimité peut être reconnu sous n'importe quel type « text/… » selon
            // son contenu (text/x-matlab pour des nombres, par exemple) : la famille suffit.
            boolean textual = format == ImportFormat.DELIMITED && "text".equals(mediaType.getType());
            if (!textual && !format.accepts(baseType)) {
                throw new ImportRejectedException("import.error.type-inattendu");
            }
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    /**
     * Un fichier Parquet commence par « PAR1 ». Tika ne sait pas le distinguer d'un binaire
     * quelconque : ces quatre octets sont donc le seul contrôle de contenu qui ait du sens ici.
     */
    private static void checkParquetMagic(Path file) {
        var magic = ImportFormat.PARQUET_MAGIC.getBytes(StandardCharsets.US_ASCII);
        try (var in = Files.newInputStream(file)) {
            if (!Arrays.equals(in.readNBytes(magic.length), magic)) {
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
