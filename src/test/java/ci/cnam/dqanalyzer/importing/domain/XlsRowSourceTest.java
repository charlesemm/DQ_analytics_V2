package ci.cnam.dqanalyzer.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import ci.cnam.dqanalyzer.testsupport.Workbooks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lecture d'un classeur Excel 97-2003 (.xls) : mêmes conventions que les autres tableurs (IMP-02). */
class XlsRowSourceTest {

    private static final SheetSettings PREMIERE_FEUILLE = SheetSettings.defaults(ImportFormat.XLS);

    @Test
    void lit_les_colonnes_et_garde_le_numero_de_ligne_du_tableur(@TempDir Path directory) throws IOException {
        Path classeur = classeur(
                directory,
                Workbooks.sheet(
                        "Assurés",
                        List.of(
                                List.of("id", "nom", "naissance"),
                                List.of("1", "Koné Aminata", LocalDate.of(1994, 12, 31)),
                                List.of("2", "Yao Désiré", LocalDate.of(1987, 1, 2)))));

        try (var source = XlsRowSource.open(classeur, PREMIERE_FEUILLE)) {
            assertThat(source.columns()).containsExactly("id", "nom", "naissance");
            assertThat(rows(source))
                    .containsExactly(
                            List.of("2", "1", "Koné Aminata", "31/12/1994"),
                            List.of("3", "2", "Yao Désiré", "02/01/1987"));
        }
    }

    @Test
    void lit_la_feuille_demandee_et_refuse_celle_qui_n_existe_pas(@TempDir Path directory) throws IOException {
        Path classeur = classeur(
                directory,
                Workbooks.sheet("Notice", List.of(List.of("mode d'emploi"))),
                Workbooks.sheet("Données", List.of(List.of("id"), List.of("1"))));

        try (var source = XlsRowSource.open(classeur, PREMIERE_FEUILLE.withSheetName("Données"))) {
            assertThat(rows(source)).containsExactly(List.of("2", "1"));
        }
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> XlsRowSource.open(classeur, PREMIERE_FEUILLE.withSheetName("Absente")))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.feuille-introuvable");
        assertThat(XlsRowSource.sheetNames(classeur)).containsExactly("Notice", "Données");
    }

    @Test
    void ignore_les_lignes_vides_et_les_cellules_vides(@TempDir Path directory) throws IOException {
        Path classeur = classeur(
                directory,
                Workbooks.sheet(
                        "Feuil1",
                        List.of(
                                List.of("id", "nom", "ville"),
                                Arrays.asList("1", null, "Abidjan"),
                                List.of(),
                                List.of("2", "Yao"))));

        try (var source = XlsRowSource.open(classeur, PREMIERE_FEUILLE)) {
            assertThat(rows(source))
                    .containsExactly(Arrays.asList("2", "1", null, "Abidjan"), Arrays.asList("4", "2", "Yao", null));
        }
    }

    @Test
    void numerote_a_partir_de_un_quand_la_feuille_n_a_pas_d_en_tete(@TempDir Path directory) throws IOException {
        Path classeur =
                classeur(directory, Workbooks.sheet("Feuil1", List.of(List.of("1", "Koné"), List.of("2", "Yao"))));

        try (var source = XlsRowSource.open(classeur, PREMIERE_FEUILLE.withHeaderRow(SheetSettings.NO_HEADER))) {
            assertThat(source.columns()).containsExactly("colonne_1", "colonne_2");
            assertThat(rows(source)).containsExactly(List.of("1", "1", "Koné"), List.of("2", "2", "Yao"));
        }
    }

    @Test
    void refuse_un_fichier_qui_n_est_pas_un_classeur(@TempDir Path directory) throws IOException {
        Path faux = directory.resolve("faux.xls");
        Files.writeString(faux, "id;nom\n1;Koné\n", StandardCharsets.UTF_8);

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> XlsRowSource.open(faux, PREMIERE_FEUILLE))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.classeur-illisible");
    }

    /** Le n° de ligne d'origine en tête, puis les valeurs de la ligne. */
    private static List<List<String>> rows(XlsRowSource source) {
        var all = new ArrayList<List<String>>();
        while (source.next()) {
            var row = new ArrayList<String>();
            row.add(String.valueOf(source.rowNumber()));
            for (int column = 0; column < source.columns().size(); column++) {
                row.add(source.field(column));
            }
            all.add(row);
        }
        return all;
    }

    private static Path classeur(Path directory, Workbooks.Sheet... sheets) {
        return Workbooks.writeXls(directory.resolve("classeur.xls"), sheets);
    }
}
