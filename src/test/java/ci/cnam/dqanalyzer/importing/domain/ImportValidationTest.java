package ci.cnam.dqanalyzer.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.testsupport.Workbooks;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** IMP-05 : refuser un fichier vide, corrompu ou trop grand. Le type réel est vérifié, pas l'extension seule. */
class ImportValidationTest {

    private static final long TEN_MEGABYTES = 10L * 1024 * 1024;

    private final ImportValidation validation = new ImportValidation(TEN_MEGABYTES);

    @Test
    void accepte_un_csv() {
        assertThatCode(() -> validation.checkAcceptable(fixture("utf8_virgule.csv"), "donnees.csv"))
                .doesNotThrowAnyException();
    }

    @Test
    void accepte_un_tsv_et_un_txt() {
        assertThatCode(() -> validation.checkAcceptable(fixture("tabule.tsv"), "donnees.tsv"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validation.checkAcceptable(fixture("utf8_virgule.csv"), "donnees.txt"))
                .doesNotThrowAnyException();
    }

    @Test
    void accepte_un_fichier_windows_1252() {
        assertThatCode(() -> validation.checkAcceptable(fixture("win1252_pointvirgule.csv"), "assures.csv"))
                .doesNotThrowAnyException();
    }

    @Test
    void accepte_un_classeur_excel(@TempDir Path directory) {
        Path classeur = Workbooks.write(
                directory.resolve("assures.xlsx"), Workbooks.sheet("Feuil1", List.of(List.of("id"), List.of("1"))));

        assertThat(validation.checkAcceptable(classeur, "assures.xlsx")).isEqualTo(ImportFormat.XLSX);
    }

    @Test
    void refuse_un_fichier_texte_renomme_en_xlsx(@TempDir Path directory) throws IOException {
        Path faux = directory.resolve("assures.xlsx");
        Files.writeString(faux, "id;nom\n1;Koné\n", StandardCharsets.UTF_8);

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> validation.checkAcceptable(faux, "assures.xlsx"))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.type-inattendu");
    }

    @Test
    void refuse_une_extension_non_prevue() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> validation.checkAcceptable(fixture("utf8_virgule.csv"), "donnees.pdf"))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.extension-refusee");
    }

    @Test
    void refuse_un_fichier_sans_extension() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> validation.checkAcceptable(fixture("utf8_virgule.csv"), "donnees"))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.extension-refusee");
    }

    @Test
    void refuse_un_executable_renomme_en_csv() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> validation.checkAcceptable(fixture("faux.csv"), "assures.csv"))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.type-inattendu");
    }

    @Test
    void refuse_un_fichier_vide() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> validation.checkAcceptable(fixture("vide.csv"), "assures.csv"))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.fichier-vide");
    }

    @Test
    void refuse_un_fichier_trop_grand() {
        var strict = new ImportValidation(20);

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> strict.checkAcceptable(fixture("utf8_virgule.csv"), "assures.csv"))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.fichier-trop-grand");
    }

    private static Path fixture(String name) {
        var url = Objects.requireNonNull(
                ImportValidationTest.class.getClassLoader().getResource("imports/" + name), "fixture " + name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
