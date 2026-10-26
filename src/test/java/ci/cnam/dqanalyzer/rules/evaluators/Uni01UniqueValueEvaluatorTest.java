package ci.cnam.dqanalyzer.rules.evaluators;

import static org.assertj.core.api.Assertions.assertThat;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
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
 * UNI-01 sur un n° d'immatriculation. Jeu de test : 9 lignes (n° de ligne 2 à 10), un groupe de 2
 * (A100 lignes 2 et 4), un groupe de 3 (B200 lignes 3, 6, 7), une valeur unique, une absente,
 * une blanche et une qui ne diffère que par la casse (ligne 10).
 */
class Uni01UniqueValueEvaluatorTest {

    private final Uni01UniqueValueEvaluator evaluator = new Uni01UniqueValueEvaluator();
    private Connection connection;
    private Dataset dataset;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        dataset = TestDatasets.loadCsv(connection, "datasets/uni01_immatriculation.csv");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void declare_le_type_et_la_dimension_du_catalogue() {
        assertThat(evaluator.type()).isEqualTo("UNI-01");
        assertThat(evaluator.dimension()).isEqualTo(Dimension.UNIQUENESS);
        assertThat(evaluator.paramSchema().params()).isEmpty();
    }

    @Test
    void compte_les_lignes_en_doublon_et_les_groupes() {
        var anomalies = new ArrayList<Anomaly>();

        var result =
                evaluator.evaluate(dataset, uniqueRule(Normalization.NONE), ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(7);
        assertThat(result.conformingRows()).isEqualTo(2);
        assertThat(result.anomalyRows()).isEqualTo(5);
        assertThat(result.duplicateGroups()).isEqualTo(2L);
    }

    @Test
    void numerote_les_groupes_dans_l_ordre_du_fichier() {
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, uniqueRule(Normalization.NONE), ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(2L, 4L, 3L, 6L, 7L);
        assertThat(anomalies).extracting(Anomaly::duplicateGroup).containsExactly(1L, 1L, 2L, 2L, 2L);
        assertThat(anomalies).extracting(Anomaly::value).containsExactly("A100", "A100", "B200", "B200", "B200");
        assertThat(anomalies).allSatisfy(a -> {
            assertThat(a.column()).isEqualTo("immatriculation");
            assertThat(a.message()).contains("immatriculation").contains("unique");
        });
    }

    @Test
    void valeurs_absentes_ou_blanches_hors_perimetre() {
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, uniqueRule(Normalization.NONE), ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).extracting(Anomaly::rowNum).doesNotContain(8L, 9L);
    }

    @Test
    void sans_normalisation_la_casse_distingue_deux_valeurs() {
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, uniqueRule(Normalization.NONE), ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).extracting(Anomaly::rowNum).doesNotContain(10L);
    }

    @Test
    void casse_ignoree_apres_normalisation() {
        var rule = uniqueRule(new Normalization(true, true, false));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(7);
        assertThat(result.conformingRows()).isEqualTo(1);
        assertThat(result.duplicateGroups()).isEqualTo(2L);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(2L, 4L, 10L, 3L, 6L, 7L);
        assertThat(anomalies).extracting(Anomaly::duplicateGroup).containsExactly(1L, 1L, 1L, 2L, 2L, 2L);
        assertThat(anomalies).extracting(Anomaly::value).contains("a100");
    }

    @Test
    void fichier_sans_doublon_aucun_groupe() {
        var rule = Rules.rule("UNI-01", Dimension.UNIQUENESS, List.of("id"), Map.of());
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(9);
        assertThat(result.conformingRows()).isEqualTo(9);
        assertThat(result.duplicateGroups()).isZero();
        assertThat(anomalies).isEmpty();
    }

    @Test
    void message_personnalise_de_la_regle_utilise() {
        var rule = new RuleDefinition(
                1L,
                "R-UNI",
                1,
                "Immatriculation unique",
                "UNI-01",
                Dimension.UNIQUENESS,
                List.of("immatriculation"),
                Map.of(),
                Normalization.NONE,
                ci.cnam.dqanalyzer.rules.Severity.BLOCKING,
                100.0,
                "Immatriculation en double.");
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).allSatisfy(a -> assertThat(a.message()).isEqualTo("Immatriculation en double."));
    }

    private static RuleDefinition uniqueRule(Normalization normalization) {
        return Rules.rule("UNI-01", Dimension.UNIQUENESS, List.of("immatriculation"), Map.of(), normalization);
    }
}
