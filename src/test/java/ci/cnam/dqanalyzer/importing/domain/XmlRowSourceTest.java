package ci.cnam.dqanalyzer.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.XmlSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lecture d'un fichier XML : l'élément répété vaut une ligne, ses sous-éléments et ses attributs
 * valent les colonnes, les niveaux imbriqués sont mis à plat (CDC §5.1 bis).
 */
class XmlRowSourceTest {

    private static final String ASSURES =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <assures>
              <assure id="1">
                <nom>Koné Aminata</nom>
                <adresse type="principale">
                  <ville>Abidjan</ville>
                </adresse>
                <telephone>01</telephone>
                <telephone>02</telephone>
              </assure>
              <assure id="2">
                <nom>Yao Désiré</nom>
                <adresse type="secondaire">
                  <ville>Bouaké</ville>
                </adresse>
              </assure>
            </assures>
            """;

    @Test
    void met_a_plat_les_sous_elements_les_attributs_et_les_repetitions(@TempDir Path directory) throws IOException {
        Path fichier = fichier(directory, ASSURES);

        try (var source = XmlRowSource.open(fichier, XmlSettings.undetermined())) {
            assertThat(source.rowElement()).isEqualTo("assure");
            assertThat(source.columns()).containsExactly("@id", "nom", "adresse.@type", "adresse.ville", "telephone");
            assertThat(rows(source))
                    .containsExactly(
                            List.of("1", "1", "Koné Aminata", "principale", "Abidjan", "01 | 02"),
                            List.of("2", "2", "Yao Désiré", "secondaire", "Bouaké"));
        }
    }

    @Test
    void lit_l_element_repete_demande(@TempDir Path directory) throws IOException {
        Path fichier = fichier(
                directory,
                """
                <export>
                  <entete><date>12/03/2025</date></entete>
                  <lignes>
                    <ligne><id>1</id></ligne>
                    <ligne><id>2</id></ligne>
                  </lignes>
                </export>
                """);

        try (var source = XmlRowSource.open(fichier, new XmlSettings("lignes/ligne"))) {
            assertThat(source.columns()).containsExactly("id");
            assertThat(rows(source)).containsExactly(List.of("1", "1"), List.of("2", "2"));
        }
    }

    @Test
    void signale_une_ligne_qui_porte_une_colonne_inconnue(@TempDir Path directory) throws IOException {
        Path fichier = fichier(
                directory,
                """
                <assures>
                  <assure><nom>Koné</nom></assure>
                  <assure><nom>Yao</nom><ville>Bouaké</ville></assure>
                </assures>
                """);

        try (var source = XmlRowSource.open(fichier, XmlSettings.undetermined())) {
            source.next();
            assertThat(source.fieldCount()).isEqualTo(1);
            source.next();
            assertThat(source.fieldCount()).isEqualTo(2); // « ville » n'a pas de colonne où aller
        }
    }

    @Test
    void refuse_un_fichier_sans_aucun_enregistrement(@TempDir Path directory) throws IOException {
        Path fichier = fichier(directory, "<assures></assures>\n");

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> XmlRowSource.open(fichier, XmlSettings.undetermined()))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.aucun-enregistrement");
    }

    /** Le n° de ligne d'origine en tête, puis les valeurs de la ligne, les absentes en moins. */
    private static List<List<String>> rows(XmlRowSource source) {
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
        Path file = directory.resolve("assures.xml");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
