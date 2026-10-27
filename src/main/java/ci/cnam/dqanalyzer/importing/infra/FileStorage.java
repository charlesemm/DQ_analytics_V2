package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
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
    private static final String CHUNK_SUFFIX = ".part";
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private final Path sources;
    private final Path databases;
    private final Path chunks;

    FileStorage(ImportProperties properties) {
        this.sources = properties.storage().path().resolve("sources");
        this.databases = properties.storage().path().resolve("duckdb");
        this.chunks = properties.storage().path().resolve("uploads");
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

    /**
     * Écrit un morceau d'un envoi en cours (IMP-01). Les morceaux sont des fichiers numérotés dans
     * un dossier propre à l'envoi : ce sont eux qui disent ce qui est déjà arrivé, et ils survivent à
     * un redémarrage du serveur.
     *
     * @return la taille du morceau écrit
     * @throws ImportRejectedException si le morceau dépasse {@code maxChunkBytes}
     */
    long writeChunk(long uploadId, int chunkNumber, InputStream content, long maxChunkBytes) {
        Path target = directory(chunks.resolve(String.valueOf(uploadId))).resolve(chunkNumber + CHUNK_SUFFIX);
        long size = 0;
        try (var out = Files.newOutputStream(target)) {
            var buffer = new byte[BUFFER];
            int read;
            while ((read = content.read(buffer)) > 0) {
                size += read;
                if (size > maxChunkBytes) {
                    throw new ImportRejectedException("import.error.morceau-trop-grand");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            delete(target);
            throw new ImportRejectedException("import.error.morceau-illisible", chunkNumber);
        } catch (RuntimeException e) {
            delete(target);
            throw e;
        }
        return size;
    }

    /** Numéros des morceaux déjà reçus, dans l'ordre. */
    List<Integer> chunkNumbers(long uploadId) {
        Path directory = chunks.resolve(String.valueOf(uploadId));
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(CHUNK_SUFFIX))
                    .map(name -> name.substring(0, name.length() - CHUNK_SUFFIX.length()))
                    .map(FileStorage::chunkNumber)
                    .filter(number -> number >= 0)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Morceaux illisibles de l'envoi " + uploadId, e);
        }
    }

    /** Total déjà reçu pour cet envoi, en octets. */
    long receivedBytes(long uploadId) {
        long total = 0;
        for (int number : chunkNumbers(uploadId)) {
            try {
                total += Files.size(chunkFile(uploadId, number));
            } catch (IOException e) {
                throw new IllegalStateException("Morceau illisible de l'envoi " + uploadId, e);
            }
        }
        return total;
    }

    /**
     * Ouvre les morceaux bout à bout, comme s'ils étaient un seul fichier : l'assemblage se lit donc
     * en flux, sans recopier 2 Go en mémoire ni créer de fichier intermédiaire (PERF-03).
     */
    InputStream openAssembled(long uploadId, List<Integer> numbers) throws IOException {
        var streams = new ArrayList<InputStream>(numbers.size());
        try {
            for (int number : numbers) {
                streams.add(Files.newInputStream(chunkFile(uploadId, number)));
            }
        } catch (IOException e) {
            for (InputStream stream : streams) {
                stream.close();
            }
            throw e;
        }
        return new SequenceInputStream(Collections.enumeration(streams));
    }

    /** Efface les morceaux et leur dossier : envoi assemblé, abandonné ou refusé. */
    void deleteChunks(long uploadId) {
        Path directory = chunks.resolve(String.valueOf(uploadId));
        if (!Files.isDirectory(directory)) {
            return;
        }
        for (int number : chunkNumbers(uploadId)) {
            delete(chunkFile(uploadId, number));
        }
        delete(directory);
    }

    private Path chunkFile(long uploadId, int chunkNumber) {
        return chunks.resolve(String.valueOf(uploadId)).resolve(chunkNumber + CHUNK_SUFFIX);
    }

    private static int chunkNumber(String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            return -1; // fichier étranger au découpage : ignoré
        }
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
