package ci.cnam.dqanalyzer.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Archives .zip à un seul fichier (IMP-09) : ce qui est accepté, et ce qui est refusé clairement. */
class SingleEntryArchiveTest {

    private static final String CSV = "id;nom\n1;Koné\n";

    @Test
    void donne_le_nom_et_le_contenu_du_seul_fichier(@TempDir Path directory) throws IOException {
        Path archive = archive(directory, Map.of("assures.csv", CSV));

        try (var content = SingleEntryArchive.open(archive)) {
            assertThat(content.entryName()).isEqualTo("assures.csv");
            assertThat(new String(content.content().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(CSV);
        }
    }

    @Test
    void ne_garde_que_le_nom_du_fichier_quand_il_est_dans_un_dossier(@TempDir Path directory) throws IOException {
        Path archive = archive(directory, Map.of("extractions/2025/assures.csv", CSV));

        try (var content = SingleEntryArchive.open(archive)) {
            assertThat(content.entryName()).isEqualTo("assures.csv");
        }
    }

    @Test
    void ignore_les_fichiers_techniques_des_systemes_d_exploitation(@TempDir Path directory) throws IOException {
        var entries = new LinkedHashMap<String, String>();
        entries.put("__MACOSX/._assures.csv", "parasite");
        entries.put("assures.csv", CSV);
        entries.put(".DS_Store", "parasite");
        entries.put("Thumbs.db", "parasite");
        Path archive = archive(directory, entries);

        try (var content = SingleEntryArchive.open(archive)) {
            assertThat(content.entryName()).isEqualTo("assures.csv");
        }
    }

    @Test
    void refuse_une_archive_vide(@TempDir Path directory) throws IOException {
        Path archive = archive(directory, Map.of());

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> SingleEntryArchive.open(archive))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.archive-vide");
    }

    @Test
    void refuse_une_archive_qui_contient_plusieurs_fichiers(@TempDir Path directory) throws IOException {
        var entries = new LinkedHashMap<String, String>();
        entries.put("assures.csv", CSV);
        entries.put("contrats.csv", CSV);
        Path archive = archive(directory, entries);

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> SingleEntryArchive.open(archive))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.archive-multiple");
    }

    @Test
    void refuse_un_fichier_qui_n_est_pas_une_archive(@TempDir Path directory) throws IOException {
        Path faux = directory.resolve("assures.zip");
        Files.writeString(faux, CSV, StandardCharsets.UTF_8);

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> SingleEntryArchive.open(faux))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.archive-illisible");
    }

    private static Path archive(Path directory, Map<String, String> entries) throws IOException {
        Path file = directory.resolve("extraction.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            if (entries.isEmpty()) {
                out.putNextEntry(new ZipEntry("dossier/"));
                out.closeEntry();
            }
        }
        return file;
    }
}
