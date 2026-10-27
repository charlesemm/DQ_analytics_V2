package ci.cnam.dqanalyzer.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.JsonSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lecture d'un fichier JSON : un objet vaut une ligne, ses propriétés valent les colonnes, les
 * objets imbriqués sont mis à plat (CDC §5.1 bis).
 */
class JsonRowSourceTest {

    @Test
    void met_a_plat_les_objets_imbriques_et_les_tableaux(@TempDir Path directory) throws IOException {
        Path fichier = fichier(
                directory,
                """
                [
                  {"id": "1", "nom": "Koné Aminata", "adresse": {"ville": "Abidjan"}, "telephones": ["01", "02"]},
                  {"id": "2", "nom": "Yao Désiré", "adresse": {"ville": "Bouaké"}, "telephones": []}
                ]
                """);

        try (var source = JsonRowSource.open(fichier, JsonSettings.root())) {
            assertThat(source.columns()).containsExactly("id", "nom", "adresse.ville", "telephones");
            assertThat(rows(source))
                    .containsExactly(
                            List.of("1", "1", "Koné Aminata", "Abidjan", "01 | 02"),
                            List.of("2", "2", "Yao Désiré", "Bouaké"));
        }
    }

    @Test
    void lit_un_objet_par_ligne(@TempDir Path directory) throws IOException {
        Path fichier = fichier(
                directory,
                """
                {"id": "1", "nom": "Koné"}
                {"id": "2", "nom": "Yao"}
                """);

        try (var source = JsonRowSource.open(fichier, JsonSettings.root())) {
            assertThat(source.columns()).containsExactly("id", "nom");
            assertThat(rows(source)).containsExactly(List.of("1", "1", "Koné"), List.of("2", "2", "Yao"));
        }
    }

    @Test
    void lit_le_tableau_designe_par_son_chemin(@TempDir Path directory) throws IOException {
        Path fichier = fichier(
                directory,
                """
                {"entete": {"date": "12/03/2025"},
                 "donnees": {"assures": [{"id": "1"}, {"id": "2"}]}}
                """);

        try (var source = JsonRowSource.open(fichier, new JsonSettings("donnees/assures"))) {
            assertThat(source.columns()).containsExactly("id");
            assertThat(rows(source)).containsExactly(List.of("1", "1"), List.of("2", "2"));
        }
    }

    @Test
    void range_les_objets_d_un_tableau_par_leur_rang(@TempDir Path directory) throws IOException {
        Path fichier = fichier(
                directory,
                """
                [{"id": "1", "enfants": [{"nom": "Awa"}, {"nom": "Ali"}]}]
                """);

        try (var source = JsonRowSource.open(fichier, JsonSettings.root())) {
            assertThat(source.columns()).containsExactly("id", "enfants.1.nom", "enfants.2.nom");
            assertThat(rows(source)).containsExactly(List.of("1", "1", "Awa", "Ali"));
        }
    }

    @Test
    void refuse_un_chemin_qui_n_existe_pas(@TempDir Path directory) throws IOException {
        Path fichier = fichier(directory, "{\"donnees\": [{\"id\": \"1\"}]}\n");

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> JsonRowSource.open(fichier, new JsonSettings("absent/assures")))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.chemin-introuvable");
    }

    @Test
    void refuse_un_fichier_sans_aucun_objet(@TempDir Path directory) throws IOException {
        Path fichier = fichier(directory, "[]\n");

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> JsonRowSource.open(fichier, JsonSettings.root()))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.aucun-enregistrement");
    }

    /** Le n° de ligne d'origine en tête, puis les valeurs de la ligne, les absentes en moins. */
    private static List<List<String>> rows(JsonRowSource source) {
        var all = new ArrayList<List<String>>();
        while (source.next()) {
            var row = new ArrayList<String>();
            row.add(String.valueOf(source.rowNumber()));
            for (int column = 0; column < source.columns().size(); column++) {
                String value = source.field(column);
                if (value != null) {
                    row.add(value);
                }
            }
            all.add(row);
        }
        return all;
    }

    private static Path fichier(Path directory, String content) throws IOException {
        Path file = directory.resolve("assures.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
