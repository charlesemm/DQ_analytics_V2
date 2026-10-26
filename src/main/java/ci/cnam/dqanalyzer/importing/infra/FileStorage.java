package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Écrit le fichier reçu sur le disque et calcule son empreinte SHA-256 dans le même passage
 * (IMP-06) : le fichier n'est jamais assemblé en mémoire (PERF-03).
 *
 * <p>La copie prend un nom tiré au hasard. Le nom donné par l'utilisateur n'est conservé qu'en base :
 * il ne sert jamais à fabriquer un chemin, ce qui ferme la porte aux noms du type {@code ../../etc}.
 */
@Component
class FileStorage {

    private static final int BUFFER = 64 * 1024;
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private final Path sources;
    private final Path databases;

    FileStorage(ImportProperties properties) {
        this.sources = properties.storage().path().resolve("sources");
        this.databases = properties.storage().path().resolve("duckdb");
    }

    /**
     * Copie le flux sur le disque en comptant les octets au fur et à mesure.
     *
     * @throws ImportRejectedException si le fichier dépasse {@code maxSizeBytes} ; la copie partielle
     *     est alors supprimée, pour ne pas remplir le disque avec un fichier refusé
     */
    StoredFile store(InputStream content, long maxSizeBytes) {
        Path target = directory(sources).resolve(UUID.randomUUID() + ".dat");
        var digest = sha256Digest();
        long size = 0;
        try (var in = new DigestInputStream(content, digest);
                var out = Files.newOutputStream(target)) {
            var buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) > 0) {
                size += read;
                if (size > maxSizeBytes) {
                    throw new ImportRejectedException(
                            "import.error.fichier-trop-grand", Math.max(1L, maxSizeBytes / BYTES_PER_MEGABYTE));
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            delete(target);
            throw new ImportRejectedException("import.error.fichier-illisible");
        } catch (RuntimeException e) {
            delete(target);
            throw e;
        }
        if (size == 0) {
            delete(target);
            throw new ImportRejectedException("import.error.fichier-vide");
        }
        return new StoredFile(target, size, HexFormat.of().formatHex(digest.digest()));
    }

    /** Base analytique de ce fichier : un fichier DuckDB par import, sur disque (PERF-03). */
    Path databaseFor(long sourceFileId) {
        return directory(databases).resolve(sourceFileId + ".duckdb");
    }

    void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Le fichier restera jusqu'à la purge du répertoire de travail (SEC-04).
        }
    }

    private static Path directory(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Répertoire de travail inutilisable : " + directory, e);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible sur cette machine virtuelle", e);
        }
    }
}
