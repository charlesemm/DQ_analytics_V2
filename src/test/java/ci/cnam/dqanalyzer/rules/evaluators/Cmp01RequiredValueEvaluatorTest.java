package ci.cnam.dqanalyzer.rules.evaluators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
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

class Cmp01RequiredValueEvaluatorTest {

    private final Cmp01RequiredValueEvaluator evaluator = new Cmp01RequiredValueEvaluator();
    private Connection connection;
    private Dataset dataset;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        dataset = TestDatasets.loadCsv(connection, "datasets/cmp01_num_secu.csv");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void detecte_valeurs_vides_espaces_et_absentes() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("num_secu"), Map.of());
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(6);
        assertThat(result.conformingRows()).isEqualTo(3);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(3L, 4L, 5L);
        assertThat(anomalies).extracting(Anomaly::value).containsExactly("", "   ", null);
        assertThat(anomalies).allSatisfy(a -> {
            assertThat(a.column()).isEqualTo("num_secu");
            assertThat(a.message()).contains("num_secu");
        });
    }

    @Test
    void colonne_entierement_renseignee_aucune_anomalie() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("id"), Map.of());
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.anomalyRows()).isZero();
        assertThat(anomalies).isEmpty();
    }

    @Test
    void colonne_inconnue_message_clair() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("telephone"), Map.of());

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("telephone");
    }

    @Test
    void nom_de_colonne_malveillant_neutralise() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("x\" OR 1=1 --"), Map.of());

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class);
    }
}
