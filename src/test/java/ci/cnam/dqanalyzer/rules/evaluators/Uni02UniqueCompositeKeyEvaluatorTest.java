package ci.cnam.dqanalyzer.rules.evaluators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.testsupport.Rules;
import ci.cnam.dqanalyzer.testsupport.TestDatasets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UNI-02 sur nom + prénoms + date de naissance. Jeu de test : 6 lignes (n° de ligne 2 à 8), un
 * doublon exact (lignes 2 et 3), un faux doublon qui ne diffère que par les prénoms (ligne 4), un
 * doublon à la casse près (lignes 5 et 6, qui relève d'UNI-03), une clé incomplète (ligne 7) et une
 * ligne unique.
 */
class Uni02UniqueCompositeKeyEvaluatorTest {

    private static final List<String> KEY = List.of("nom", "prenoms", "date_naissance");

    private final Uni02UniqueCompositeKeyEvaluator evaluator = new Uni02UniqueCompositeKeyEvaluator();
    private Connection connection;
    private Dataset dataset;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        dataset = TestDatasets.loadCsv(connection, "datasets/uni02_identite.csv");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void declare_le_type_et_la_dimension_du_catalogue() {
        assertThat(evaluator.type()).isEqualTo("UNI-02");
        assertThat(evaluator.dimension()).isEqualTo(Dimension.UNIQUENESS);
        assertThat(evaluator.paramSchema().columns()).isEqualTo(ParamSchema.ColumnArity.MANY);
        assertThat(evaluator.paramSchema().params()).isEmpty();
    }

    @Test
    void seule_la_cle_complete_identique_est_un_doublon() {
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(
                dataset, compositeRule(KEY, Normalization.NONE), ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(6);
        assertThat(result.conformingRows()).isEqualTo(4);
        assertThat(result.duplicateGroups()).isEqualTo(1L);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(2L, 3L);
        assertThat(anomalies).extracting(Anomaly::duplicateGroup).containsExactly(1L, 1L);
    }

    @Test
    void anomalie_porte_les_colonnes_et_les_valeurs_de_la_cle() {
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, compositeRule(KEY, Normalization.NONE), ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).allSatisfy(a -> {
            assertThat(a.column()).isEqualTo("nom + prenoms + date_naissance");
            assertThat(a.value()).isEqualTo("KOUAME | Jean | 01/01/1980");
            assertThat(a.message()).contains("nom").contains("prenoms").contains("plusieurs fois");
        });
    }

    @Test
    void cle_incomplete_hors_perimetre() {
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, compositeRule(KEY, Normalization.NONE), ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).extracting(Anomaly::rowNum).doesNotContain(7L);
    }

    @Test
    void la_casse_distingue_deux_cles_sans_normalisation() {
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, compositeRule(KEY, Normalization.NONE), ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).extracting(Anomaly::rowNum).doesNotContain(5L, 6L);
    }

    @Test
    void cle_groupee_colonne_par_colonne_et_jamais_concatenee() throws Exception {
        // AB + C et A + BC ne sont pas la même clé : concaténer créerait un faux doublon
        try (var other = DriverManager.getConnection("jdbc:duckdb:")) {
            var collisions = TestDatasets.loadCsv(other, "datasets/uni02_cle_collision.csv");
            var rule = compositeRule(List.of("code", "suffixe"), Normalization.NONE);
            var anomalies = new ArrayList<Anomaly>();

            var result = evaluator.evaluate(collisions, rule, ReferenceProvider.NONE, anomalies::add);

            assertThat(result.checkedRows()).isEqualTo(2);
            assertThat(result.conformingRows()).isEqualTo(2);
            assertThat(result.duplicateGroups()).isZero();
            assertThat(anomalies).isEmpty();
        }
    }

    @Test
    void colonne_inconnue_message_clair() {
        var rule = compositeRule(List.of("nom", "adresse"), Normalization.NONE);

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("adresse");
    }

    @Test
    void aucune_colonne_ciblee_message_clair() {
        var rule = compositeRule(List.of(), Normalization.NONE);

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("aucune colonne");
    }

    private static RuleDefinition compositeRule(List<String> columns, Normalization normalization) {
        return Rules.rule("UNI-02", Dimension.UNIQUENESS, columns, Map.of(), normalization);
    }
}
