package ci.cnam.dqanalyzer.profiling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ci.cnam.dqanalyzer.profiling.ColumnProfile;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.testsupport.TestDatasets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Profil du jeu de recette {@code docs/recette/assures.csv} (PRF-01) : il porte les cas qui mettent
 * le profilage en difficulté — une cellule vide, un nombre négatif, des accents, des dates, et une
 * colonne où une valeur se répète.
 */
class RecetteProfilTest {

    private final ColumnProfiler profiler = new ColumnProfiler();
    private Connection connection;
    private List<ColumnProfile> profiles;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        Dataset dataset = TestDatasets.loadCsv(connection, "datasets/recette_assures.csv");
        profiles = profiler.profile(dataset);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void les_six_colonnes_sont_profilees() {
        assertThat(profiles)
                .extracting(ColumnProfile::column)
                .containsExactly("matricule", "nom", "sexe", "date_naissance", "commune", "salaire");
    }

    @Test
    void le_remplissage_et_les_valeurs_distinctes_correspondent_au_scenario() {
        var sexe = profil("sexe");
        assertThat(sexe.rowCount()).isEqualTo(12);
        assertThat(sexe.filled()).isEqualTo(11); // la ligne 4 n'a pas de sexe

        var matricule = profil("matricule");
        assertThat(matricule.rowCount()).isEqualTo(12);
        assertThat(matricule.distinctValues()).isEqualTo(11); // CI000004 compte double
    }

    @Test
    void le_salaire_vide_et_le_salaire_negatif_ne_font_pas_echouer_le_profil() {
        var salaire = profil("salaire");

        assertThat(salaire.filled()).isEqualTo(11); // la ligne 12 n'a pas de salaire
        assertThat(salaire.detectedType()).isEqualTo("ENTIER");
        assertThat(salaire.minValue()).isNotNull();
    }

    @Test
    void la_colonne_de_dates_est_reconnue_comme_date() {
        assertThat(profil("date_naissance").detectedType()).isEqualTo("DATE");
    }

    private ColumnProfile profil(String column) {
        return profiles.stream()
                .filter(profile -> profile.column().equals(column))
                .findFirst()
                .orElseThrow();
    }
}
