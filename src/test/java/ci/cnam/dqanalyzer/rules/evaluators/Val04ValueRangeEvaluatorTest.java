package ci.cnam.dqanalyzer.rules.evaluators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
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
 * VAL-04 sur un âge (0 à 120) et un taux décimal (0 à 1). Jeu de test : 7 lignes (n° de ligne 2 à 8),
 * une valeur hors borne basse, une hors borne haute, une non numérique, une absente et les deux bornes.
 */
class Val04ValueRangeEvaluatorTest {

    private final Val04ValueRangeEvaluator evaluator = new Val04ValueRangeEvaluator();
    private Connection connection;
    private Dataset dataset;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        dataset = TestDatasets.loadCsv(connection, "datasets/val04_age.csv");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void declare_le_type_et_la_dimension_du_catalogue() {
        assertThat(evaluator.type()).isEqualTo("VAL-04");
        assertThat(evaluator.dimension()).isEqualTo(Dimension.VALIDITY);
        assertThat(evaluator.paramSchema().params()).extracting(p -> p.name()).containsExactly("min", "max");
    }

    @Test
    void detecte_hors_plage_et_valeur_non_numerique_bornes_incluses() {
        var rule = rangeRule("age", Map.of("min", 0, "max", 120));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(6);
        assertThat(result.conformingRows()).isEqualTo(3);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(3L, 4L, 5L);
        assertThat(anomalies).extracting(Anomaly::value).containsExactly("-3", "150", "abc");
        assertThat(anomalies).allSatisfy(a -> {
            assertThat(a.column()).isEqualTo("age");
            assertThat(a.message()).contains("age").contains("0").contains("120");
        });
    }

    @Test
    void valeur_absente_hors_perimetre() {
        var rule = rangeRule("age", Map.of("min", 0, "max", 120));
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).extracting(Anomaly::rowNum).doesNotContain(6L);
    }

    @Test
    void bornes_decimales() {
        var rule = rangeRule("taux", Map.of("min", 0.0, "max", 1.0));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(7);
        assertThat(result.conformingRows()).isEqualTo(5);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(7L, 8L);
        assertThat(anomalies).extracting(Anomaly::value).containsExactly("1.5", "-0.1");
    }

    @Test
    void minimum_seul() {
        var rule = rangeRule("age", Map.of("min", 18));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(3);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(3L, 5L, 7L);
    }

    @Test
    void maximum_seul() {
        var rule = rangeRule("age", Map.of("max", 120));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(4);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(4L, 5L);
    }

    @Test
    void parametre_texte_accepte_depuis_le_json() {
        var rule = rangeRule("age", Map.of("min", "0", "max", "120"));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(3);
    }

    @Test
    void aucune_borne_message_clair() {
        var rule = rangeRule("age", Map.of());

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("min")
                .hasMessageContaining("max");
    }

    @Test
    void minimum_superieur_au_maximum_refuse() {
        var rule = rangeRule("age", Map.of("min", 120, "max", 0));

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class);
    }

    @Test
    void borne_non_numerique_refusee() {
        var rule = rangeRule("age", Map.of("max", "cent vingt"));

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("max");
    }

    private static RuleDefinition rangeRule(String column, Map<String, Object> params) {
        return Rules.rule("VAL-04", Dimension.VALIDITY, List.of(column), params);
    }
}
