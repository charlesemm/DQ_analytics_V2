package ci.cnam.dqanalyzer.importing.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * IMP-06 : le fichier est conservé et son empreinte SHA-256 est calculée pendant l'écriture, en un
 * seul passage, sans jamais tenir le contenu en mémoire (PERF-03).
 */
class FileStorageTest {

    /** Empreinte SHA-256 de « abc », valeur de référence publique de l'algorithme. */
    private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void ecrit_le_fichier_et_calcule_son_empreinte(@TempDir Path directory) throws IOException {
        var storage = storage(directory, DataSize.ofMegabytes(1));

        var stored = storage.store(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), 1_000_000);

        assertThat(stored.sizeBytes()).isEqualTo(3);
        assertThat(stored.sha256()).isEqualTo(SHA256_OF_ABC);
        assertThat(Files.readString(stored.path())).isEqualTo("abc");
    }

    @Test
    void ne_reutilise_jamais_le_nom_donne_par_l_utilisateur(@TempDir Path directory) {
        var storage = storage(directory, DataSize.ofMegabytes(1));

        var stored = storage.store(new ByteArrayInputStream("a;b".getBytes(StandardCharsets.UTF_8)), 1_000_000);

        assertThat(stored.path().getFileName().toString()).endsWith(".dat").doesNotContain("..");
        assertThat(stored.path().getParent().getFileName().toString()).isEqualTo("sources");
    }

    @Test
    void refuse_un_fichier_trop_grand_et_ne_laisse_rien_sur_le_disque(@TempDir Path directory) throws IOException {
        var storage = storage(directory, DataSize.ofBytes(100));
        var content = new byte[500_000];

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> storage.store(new ByteArrayInputStream(content), 100))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.fichier-trop-grand");
        assertThat(filesIn(directory.resolve("sources"))).isZero();
    }

    @Test
    void refuse_un_flux_vide(@TempDir Path directory) throws IOException {
        var storage = storage(directory, DataSize.ofMegabytes(1));

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> storage.store(new ByteArrayInputStream(new byte[0]), 1_000_000))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.fichier-vide");
        assertThat(filesIn(directory.resolve("sources"))).isZero();
    }

    @Test
    void range_une_base_duckdb_par_fichier_importe(@TempDir Path directory) {
        var storage = storage(directory, DataSize.ofMegabytes(1));

        assertThat(storage.databaseFor(42))
                .isEqualTo(directory.resolve("duckdb").resolve("42.duckdb"));
    }

    private static FileStorage storage(Path directory, DataSize maxFileSize) {
        return new FileStorage(new ImportProperties(
                new ImportProperties.Storage(directory),
                new ImportProperties.Upload(maxFileSize, DataSize.ofMegabytes(10))));
    }

    private static long filesIn(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }
}
