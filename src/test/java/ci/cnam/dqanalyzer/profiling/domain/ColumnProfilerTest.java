package ci.cnam.dqanalyzer.profiling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ci.cnam.dqanalyzer.profiling.ColumnProfile;
import ci.cnam.dqanalyzer.profiling.ColumnProfile.ValueCount;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.testsupport.TestDatasets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Profil des colonnes (PRF-01 à PRF-03) sur un jeu de 22 lignes (n° de ligne 2 à 23) : un matricule
 * de longueur fixe tout en valeurs différentes, un âge dont une valeur sur 22 n'est pas un nombre
 * (le seuil de 95 % doit quand même donner ENTIER), un sexe à deux valeurs, une date, un montant
 * décimal, une ville avec 2 cellules absentes et un commentaire entièrement vide.
 */
class ColumnProfilerTest {

    private final ColumnProfiler profiler = new ColumnProfiler();
    private Connection connection;
    private Map<String, ColumnProfile> profiles;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        Dataset dataset = TestDatasets.loadCsv(connection, "datasets/prf01_profil_colonnes.csv");
        profiles = profiler.profile(dataset).stream()
                .collect(Collectors.toMap(ColumnProfile::column, Function.identity()));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void profile_toutes_les_colonnes_du_fichier() {
        assertThat(profiles.keySet())
                .containsExactlyInAnyOrder(
                        "matricule", "age", "sexe", "date_naissance", "salaire", "ville", "commentaire");
    }

    @Test
    void compte_les_lignes_les_valeurs_remplies_et_les_distinctes() {
        var matricule = profiles.get("matricule");

        assertThat(matricule.rowCount()).isEqualTo(22);
        assertThat(matricule.filled()).isEqualTo(22);
        assertThat(matricule.empty()).isZero();
        assertThat(matricule.distinctValues()).isEqualTo(22);
        assertThat(matricule.fillRate()).isEqualTo(100.0);
    }

    @Test
    void ne_compte_pas_les_cellules_absentes_dans_les_longueurs_et_les_extremes() {
        var ville = profiles.get("ville");

        assertThat(ville.rowCount()).isEqualTo(22);
        assertThat(ville.filled()).isEqualTo(20);
        assertThat(ville.empty()).isEqualTo(2);
        assertThat(ville.distinctValues()).isEqualTo(4);
        // Sans le FILTER (WHERE rempli), la longueur minimale vaudrait 0 à cause des cellules absentes.
        assertThat(ville.minLength()).isEqualTo(3);
        assertThat(ville.maxLength()).isEqualTo(7);
        assertThat(ville.minValue()).isEqualTo("Abidjan");
        assertThat(ville.maxValue()).isEqualTo("Man");
    }

    @Test
    void retient_le_type_entier_malgre_une_valeur_mal_saisie() {
        var age = profiles.get("age");

        // 21 valeurs numériques sur 22 remplies, soit 95,4 % : au-dessus du seuil de 95 %.
        assertThat(age.detectedType()).isEqualTo("ENTIER");
        assertThat(age.filled()).isEqualTo(22);
    }

    @Test
    void reconnait_les_dates_les_decimaux_et_le_texte() {
        assertThat(profiles.get("date_naissance").detectedType()).isEqualTo("DATE");
        assertThat(profiles.get("salaire").detectedType()).isEqualTo("DECIMAL");
        assertThat(profiles.get("matricule").detectedType()).isEqualTo("TEXTE");
    }

    @Test
    void declare_une_colonne_entierement_vide() {
        var commentaire = profiles.get("commentaire");

        assertThat(commentaire.detectedType()).isEqualTo("VIDE");
        assertThat(commentaire.filled()).isZero();
        assertThat(commentaire.fillRate()).isZero();
        assertThat(commentaire.minLength()).isNull();
        assertThat(commentaire.maxLength()).isNull();
        assertThat(commentaire.topValues()).isEmpty();
        assertThat(commentaire.patterns()).isEmpty();
    }

    @Test
    void donne_les_valeurs_les_plus_frequentes_du_plus_frequent_au_moins_frequent() {
        assertThat(profiles.get("sexe").topValues()).containsExactly(new ValueCount("M", 12), new ValueCount("F", 10));

        // À nombre égal, l'ordre est alphabétique : Bouaké et Man apparaissent tous deux 4 fois.
        assertThat(profiles.get("ville").topValues())
                .containsExactly(
                        new ValueCount("Abidjan", 9),
                        new ValueCount("Bouaké", 4),
                        new ValueCount("Man", 4),
                        new ValueCount("Korhogo", 3));
    }

    @Test
    void limite_les_valeurs_a_dix_et_les_motifs_a_cinq() {
        var dates = profiles.get("date_naissance");

        assertThat(dates.distinctValues()).isEqualTo(22);
        assertThat(dates.topValues()).hasSize(10);
        assertThat(dates.patterns()).hasSize(1);
    }

    @Test
    void remplace_les_chiffres_par_9_les_majuscules_par_A_et_les_minuscules_par_a() {
        assertThat(profiles.get("matricule").patterns()).containsExactly(new ValueCount("AA999999", 22));
        assertThat(profiles.get("date_naissance").patterns()).containsExactly(new ValueCount("99/99/9999", 22));
        assertThat(profiles.get("sexe").patterns()).containsExactly(new ValueCount("A", 22));
        assertThat(patternsOf("age")).contains("99", "aaa");
    }

    private List<String> patternsOf(String column) {
        return profiles.get(column).patterns().stream().map(ValueCount::value).toList();
    }
}
