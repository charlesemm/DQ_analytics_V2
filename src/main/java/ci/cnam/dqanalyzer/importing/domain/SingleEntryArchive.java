package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Archive .zip ne contenant qu'un seul fichier (IMP-09) : les services envoient souvent leurs
 * extractions compressées, le fichier à analyser étant à l'intérieur.
 *
 * <p>Une archive qui en contient plusieurs est refusée plutôt que de choisir à la place de
 * l'analyste : il saura, lui, lequel il voulait analyser. Les dossiers et les fichiers techniques
 * ajoutés par les systèmes d'exploitation ne comptent pas.
 *
 * <p>Le contenu n'est pas décompressé en mémoire : il est lu en flux et recopié sur le disque par
 * l'appelant, qui lui applique les mêmes contrôles qu'à un fichier reçu directement — taille,
 * extension et type réel (SEC-03).
 */
public final class SingleEntryArchive implements Closeable {

    /** Dossiers et fichiers que macOS et Windows glissent dans les archives, à ne pas compter. */
    private static final String MACOS_METADATA = "__macosx/";

    private static final String MACOS_FILE = ".ds_store";
    private static final String WINDOWS_FILE = "thumbs.db";

    private final ZipFile archive;
    private final ZipEntry entry;
    private final InputStream content;

    private SingleEntryArchive(ZipFile archive, ZipEntry entry, InputStream content) {
        this.archive = archive;
        this.entry = entry;
        this.content = content;
    }

    /**
     * Ouvre l'archive et se place sur son unique fichier.
     *
     * @throws ImportRejectedException si l'archive est illisible, vide ou contient plusieurs fichiers
     */
    public static SingleEntryArchive open(Path file) {
        ZipFile archive = null;
        try {
            archive = new ZipFile(file.toFile());
            var entries = archive.stream().filter(SingleEntryArchive::isFile).toList();
            if (entries.isEmpty()) {
                throw new ImportRejectedException("import.error.archive-vide");
            }
            if (entries.size() > 1) {
                throw new ImportRejectedException("import.error.archive-multiple", entries.size());
            }
            var entry = entries.get(0);
            return new SingleEntryArchive(archive, entry, archive.getInputStream(entry));
        } catch (ImportRejectedException e) {
            closeQuietly(archive);
            throw e;
        } catch (IOException | RuntimeException e) {
            closeQuietly(archive);
            throw new ImportRejectedException("import.error.archive-illisible");
        }
    }

    /** Nom du fichier contenu, sans son chemin dans l'archive : il sert à reconnaître son format. */
    public String entryName() {
        String name = entry.getName();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return separator < 0 ? name : name.substring(separator + 1);
    }

    /** Contenu du fichier, en flux. Il est refermé avec l'archive. */
    public InputStream content() {
        return content;
    }

    @Override
    public void close() throws IOException {
        try (archive;
                content) {
            // L'archive et le flux de son fichier se ferment ensemble.
        }
    }

    private static boolean isFile(ZipEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }
        String name = entry.getName().toLowerCase(Locale.ROOT);
        return !name.startsWith(MACOS_METADATA) && !name.endsWith(MACOS_FILE) && !name.endsWith(WINDOWS_FILE);
    }

    private static void closeQuietly(ZipFile archive) {
        if (archive != null) {
            try {
                archive.close();
            } catch (IOException e) {
                // Archive ouverte en lecture seule : rien de plus à tenter.
            }
        }
    }
}
