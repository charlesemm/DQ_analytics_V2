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

/**
 * Lecture d'un classeur Excel feuille par feuille (IMP-02) : colonnes de l'en-tête, numéro de ligne
 * tel qu'Excel l'affiche, et valeurs telles que l'analyste les voit à l'écran.
 */
class XlsxRowSourceTest {

    private static final SheetSettings PREMIERE_FEUILLE = SheetSettings.defaults(ImportFormat.XLSX);

    @Test
    void lit_les_colonnes_et_garde_le_numero_de_ligne_du_tableur(@TempDir Path directory) throws IOException {
        Path classeur = classeur(
                directory,
                Workbooks.sheet(
                        "Assurés",
                        List.of(
                                List.of("id", "nom", "ville"),
                                List.of("1", "Koné Aminata", "Abidjan"),
                                List.of("2", "Yao Désiré", "Bouaké"))));

        try (var source = XlsxRowSource.open(classeur, PREMIERE_FEUILLE)) {
            assertThat(source.columns()).containsExactly("id", "nom", "ville");
            assertThat(rows(source))
                    .containsExactly(
                            List.of("2", "1", "Koné Aminata", "Abidjan"), List.of("3", "2", "Yao Désiré", "Bouaké"));
        }
    }

    @Test
    void ecrit_les_dates_et_les_nombres_comme_le_tableur_les_affiche(@TempDir Path directory) throws IOException {
        Path classeur = classeur(
                directory,
                Workbooks.sheet(
                        "Feuil1",
                        List.of(
                                List.of("naissance", "enfants", "actif"),
                                List.of(LocalDate.of(1994, 12, 31), 3, true))));

        try (var source = XlsxRowSource.open(classeur, PREMIERE_FEUILLE)) {
            assertThat(rows(source)).containsExactly(List.of("2", "31/12/1994", "3", "VRAI"));
        }
    }

    @Test
    void lit_la_feuille_demandee(@TempDir Path directory) throws IOException {
        Path classeur = classeur(
                directory,
                Workbooks.sheet("Notice", List.of(List.of("mode d'emploi"))),
                Workbooks.sheet("Données", List.of(List.of("id"), List.of("1"))));

        try (var source = XlsxRowSource.open(classeur, PREMIERE_FEUILLE.withSheetName("Données"))) {
            assertThat(source.columns()).containsExactly("id");
            assertThat(rows(source)).containsExactly(List.of("2", "1"));
        }
    }

    @Test
    void refuse_une_feuille_qui_n_existe_pas(@TempDir Path directory) {
        Path classeur = classeur(directory, Workbooks.sheet("Feuil1", List.of(List.of("id"), List.of("1"))));

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> XlsxRowSource.open(classeur, PREMIERE_FEUILLE.withSheetName("Absente")))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.feuille-introuvable");
    }

    @Test
    void prend_l_en_tete_a_la_ligne_indiquee(@TempDir Path directory) throws IOException {
        Path classeur = classeur(
                directory,
                Workbooks.sheet(
                        "Feuil1",
                        List.of(
                                List.of("Extraction du 12/03/2025"),
                                List.of(),
                                List.of("id", "nom"),
                                List.of("1", "Koné"))));

        try (var source = XlsxRowSource.open(classeur, PREMIERE_FEUILLE.withHeaderRow(3))) {
            assertThat(source.columns()).containsExactly("id", "nom");
            assertThat(rows(source)).containsExactly(List.of("4", "1", "Koné"));
        }
    }

    @Test
    void refuse_une_ligne_d_en_tete_absente_de_la_feuille(@TempDir Path directory) {
        Path classeur = classeur(directory, Workbooks.sheet("Feuil1", List.of(List.of("id"), List.of("1"))));

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> XlsxRowSource.open(classeur, PREMIERE_FEUILLE.withHeaderRow(10)))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.ligne-entete-introuvable");
    }

    @Test
    void numerote_a_partir_de_un_quand_la_feuille_n_a_pas_d_en_tete(@TempDir Path directory) throws IOException {
        Path classeur =
                classeur(directory, Workbooks.sheet("Feuil1", List.of(List.of("1", "Koné"), List.of("2", "Yao"))));

        try (var source = XlsxRowSource.open(classeur, PREMIERE_FEUILLE.withHeaderRow(SheetSettings.NO_HEADER))) {
            assertThat(source.columns()).containsExactly("colonne_1", "colonne_2");
            assertThat(rows(source)).containsExactly(List.of("1", "1", "Koné"), List.of("2", "2", "Yao"));
        }
    }

    @Test
    void une_cellule_vide_est_une_valeur_absente_et_ne_rend_pas_la_ligne_incomplete(@TempDir Path directory)
            throws IOException {
        Path classeur = classeur(
                directory,
                Workbooks.sheet(
                        "Feuil1",
                        List.of(
                                List.of("id", "nom", "ville"),
                                Arrays.asList("1", null, "Abidjan"),
                                List.of("2", "Yao")))); // dernière colonne absente du fichier

        try (var source = XlsxRowSource.open(classeur, PREMIERE_FEUILLE)) {
            source.next();
            assertThat(source.field(1)).isNull();
            assertThat(source.fieldCount()).isEqualTo(3);
            source.next();
            assertThat(source.field(2)).isNull();
            assertThat(source.fieldCount()).isEqualTo(3);
        }
    }

    @Test
    void signale_une_ligne_plus_large_que_l_en_tete(@TempDir Path directory) throws IOException {
        Path classeur = classeur(
                directory, Workbooks.sheet("Feuil1", List.of(List.of("id", "nom"), List.of("1", "Koné", "en trop"))));

        try (var source = XlsxRowSource.open(classeur, PREMIERE_FEUILLE)) {
            source.next();
            assertThat(source.fieldCount()).isEqualTo(3);
        }
    }

    @Test
    void refuse_un_fichier_qui_n_est_pas_un_classeur(@TempDir Path directory) throws IOException {
        Path faux = directory.resolve("faux.xlsx");
        Files.writeString(faux, "id;nom\n1;Koné\n", StandardCharsets.UTF_8);

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> XlsxRowSource.open(faux, PREMIERE_FEUILLE))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.classeur-illisible");
    }

    @Test
    void donne_les_noms_des_feuilles_pour_le_choix_de_l_analyste(@TempDir Path directory) {
        Path classeur = classeur(
                directory,
                Workbooks.sheet("Notice", List.of(List.of("a"))),
                Workbooks.sheet("Données", List.of(List.of("id"), List.of("1"))));

        assertThat(XlsxRowSource.sheetNames(classeur)).containsExactly("Notice", "Données");
    }

    /** Le n° de ligne d'origine en tête, puis les valeurs de la ligne. */
    private static List<List<String>> rows(XlsxRowSource source) {
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
        return Workbooks.write(directory.resolve("classeur" + sheets.length + ".xlsx"), sheets);
    }
}
