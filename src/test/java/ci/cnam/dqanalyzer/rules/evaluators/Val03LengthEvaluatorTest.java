package ci.cnam.dqanalyzer.rules.evaluators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Normalization;
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
 * VAL-03 sur un n° CNPS attendu sur 13 caractères. Jeu de test : 7 lignes (n° de ligne 2 à 8),
 * 2 valeurs absentes ou blanches (hors périmètre), 1 trop courte, 1 trop longue, 1 entourée d'espaces.
 */
class Val03LengthEvaluatorTest {

    private final Val03LengthEvaluator evaluator = new Val03LengthEvaluator();
    private Connection connection;
    private Dataset dataset;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        dataset = TestDatasets.loadCsv(connection, "datasets/val03_num_cnps.csv");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void declare_le_type_et_la_dimension_du_catalogue() {
        assertThat(evaluator.type()).isEqualTo("VAL-03");
        assertThat(evaluator.dimension()).isEqualTo(Dimension.VALIDITY);
        assertThat(evaluator.paramSchema().params()).extracting(p -> p.name()).containsExactly("min", "max");
    }

    @Test
    void longueur_exacte_detecte_trop_court_trop_long_et_espaces() {
        var rule = lengthRule(Map.of("min", 13, "max", 13), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(5);
        assertThat(result.conformingRows()).isEqualTo(2);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(3L, 4L, 8L);
        assertThat(anomalies)
                .extracting(Anomaly::value)
                .containsExactly("12345", "12345678901234567", " 1234567890125 ");
        assertThat(anomalies).allSatisfy(a -> {
            assertThat(a.column()).isEqualTo("num_cnps");
            assertThat(a.message()).contains("num_cnps");
        });
    }

    @Test
    void valeurs_absentes_ou_blanches_hors_perimetre() {
        var rule = lengthRule(Map.of("min", 13, "max", 13), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).extracting(Anomaly::rowNum).doesNotContain(5L, 6L);
    }

    @Test
    void longueur_mesuree_apres_normalisation_des_espaces() {
        var rule = lengthRule(Map.of("min", 13, "max", 13), new Normalization(true, false, false));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(5);
        assertThat(result.conformingRows()).isEqualTo(3);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(3L, 4L);
    }

    @Test
    void maximum_seul() {
        var rule = lengthRule(Map.of("max", 13), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(3);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(4L, 8L);
    }

    @Test
    void minimum_seul() {
        var rule = lengthRule(Map.of("min", 13), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(4);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(3L);
    }

    @Test
    void parametre_texte_accepte_depuis_le_json() {
        var rule = lengthRule(Map.of("min", "13", "max", "13"), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(2);
    }

    @Test
    void aucune_borne_message_clair() {
        var rule = lengthRule(Map.of(), Normalization.NONE);

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("minimale")
                .hasMessageContaining("maximale");
    }

    @Test
    void borne_non_numerique_refusee() {
        var rule = lengthRule(Map.of("min", "treize"), Normalization.NONE);

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("min");
    }

    private static RuleDefinition lengthRule(Map<String, Object> params, Normalization normalization) {
        return Rules.rule("VAL-03", Dimension.VALIDITY, List.of("num_cnps"), params, normalization);
    }
}
