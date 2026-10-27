package ci.cnam.dqanalyzer.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.FixedWidthField;
import ci.cnam.dqanalyzer.importing.FixedWidthSettings;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lecture d'un fichier à largeur fixe (IMP-08) : le gabarit dit où commence chaque colonne, les
 * blancs de remplissage sont retirés, et le numéro de ligne est celui du fichier.
 */
class FixedWidthRowSourceTest {

    private static final List<FixedWidthField> GABARIT = List.of(
            new FixedWidthField("id", 1, 3), new FixedWidthField("nom", 4, 10), new FixedWidthField("ville", 14, 8));

    @Test
    void decoupe_chaque_ligne_aux_positions_du_gabarit(@TempDir Path directory) throws IOException {
        Path fichier = fichier(
                directory,
                """
                id nom       ville
                1  Koné      Abidjan
                2  Yao       Bouaké
                """);

        try (var source = FixedWidthRowSource.open(fichier, new FixedWidthSettings("UTF-8", GABARIT, true))) {
            assertThat(source.columns()).containsExactly("id", "nom", "ville");
            assertThat(rows(source))
                    .containsExactly(List.of("2", "1", "Koné", "Abidjan"), List.of("3", "2", "Yao", "Bouaké"));
        }
    }

    @Test
    void prend_les_noms_de_colonnes_dans_l_en_tete_quand_le_gabarit_n_en_donne_pas(@TempDir Path directory)
            throws IOException {
        Path fichier = fichier(
                directory,
                """
                num        libelle
                001        Abidjan
                """);
        var gabarit = List.of(new FixedWidthField("", 1, 11), new FixedWidthField("", 12, 7));

        try (var source = FixedWidthRowSource.open(fichier, new FixedWidthSettings("UTF-8", gabarit, true))) {
            assertThat(source.columns()).containsExactly("num", "libelle");
            assertThat(rows(source)).containsExactly(List.of("2", "001", "Abidjan"));
        }
    }

    @Test
    void nomme_les_colonnes_quand_le_fichier_n_a_ni_en_tete_ni_noms(@TempDir Path directory) throws IOException {
        Path fichier = fichier(directory, "1  Koné      Abidjan\n");
        var gabarit = List.of(new FixedWidthField("", 1, 3), new FixedWidthField("", 4, 10));

        try (var source = FixedWidthRowSource.open(fichier, new FixedWidthSettings("UTF-8", gabarit, false))) {
            assertThat(source.columns()).containsExactly("colonne_1", "colonne_2");
            assertThat(rows(source)).containsExactly(List.of("1", "1", "Koné"));
        }
    }

    @Test
    void ignore_les_lignes_blanches_mais_garde_les_numeros_du_fichier(@TempDir Path directory) throws IOException {
        Path fichier = fichier(
                directory,
                """
                id nom       ville
                1  Koné      Abidjan

                2  Yao       Bouaké
                """);

        try (var source = FixedWidthRowSource.open(fichier, new FixedWidthSettings("UTF-8", GABARIT, true))) {
            assertThat(rows(source))
                    .containsExactly(List.of("2", "1", "Koné", "Abidjan"), List.of("4", "2", "Yao", "Bouaké"));
        }
    }

    @Test
    void signale_une_ligne_trop_courte_pour_le_gabarit(@TempDir Path directory) throws IOException {
        Path fichier =
                fichier(directory, """
                id nom       ville
                1  Koné
                """);

        try (var source = FixedWidthRowSource.open(fichier, new FixedWidthSettings("UTF-8", GABARIT, true))) {
            source.next();
            assertThat(source.fieldCount()).isEqualTo(2); // la colonne « ville » n'est pas atteinte
            assertThat(Arrays.asList(source.field(0), source.field(1), source.field(2)))
                    .containsExactly("1", "Koné", null);
        }
    }

    @Test
    void refuse_un_fichier_sans_gabarit(@TempDir Path directory) throws IOException {
        Path fichier = fichier(directory, "1  Koné\n");

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> FixedWidthRowSource.open(fichier, FixedWidthSettings.empty("UTF-8")))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.gabarit-absent");
    }

    /** Le n° de ligne d'origine en tête, puis les valeurs de la ligne. */
    private static List<List<String>> rows(FixedWidthRowSource source) {
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

    private static Path fichier(Path directory, String content) throws IOException {
        Path file = directory.resolve("extraction.dat");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
