package ci.cnam.dqanalyzer.rules.evaluators;

import static org.assertj.core.api.Assertions.assertThat;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.ParamSchema;
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
 * UNI-03 : mêmes groupes qu'UNI-01 et UNI-02, mais la clé passe d'abord par la normalisation de la
 * règle (espaces, majuscules, accents). La valeur « a100 » rejoint alors le groupe de « A100 ».
 */
class Uni03NormalizedDuplicateEvaluatorTest {

    private static final Normalization UPPER_TRIM = new Normalization(true, true, false);

    private final Uni03NormalizedDuplicateEvaluator evaluator = new Uni03NormalizedDuplicateEvaluator();
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void declare_le_type_et_la_dimension_du_catalogue() {
        assertThat(evaluator.type()).isEqualTo("UNI-03");
        assertThat(evaluator.dimension()).isEqualTo(Dimension.UNIQUENESS);
        assertThat(evaluator.paramSchema().columns()).isEqualTo(ParamSchema.ColumnArity.MANY);
    }

    @Test
    void casse_ignoree_sur_une_colonne() {
        var dataset = TestDatasets.loadCsv(connection, "datasets/uni01_immatriculation.csv");
        var rule = rule(List.of("immatriculation"), UPPER_TRIM);
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
    void sans_normalisation_la_casse_distingue_encore_les_valeurs() {
        var dataset = TestDatasets.loadCsv(connection, "datasets/uni01_immatriculation.csv");
        var rule = rule(List.of("immatriculation"), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.duplicateGroups()).isEqualTo(2L);
        assertThat(anomalies).extracting(Anomaly::rowNum).doesNotContain(10L);
    }

    @Test
    void casse_ignoree_sur_une_cle_composee() {
        var dataset = TestDatasets.loadCsv(connection, "datasets/uni02_identite.csv");
        var rule = rule(List.of("nom", "prenoms", "date_naissance"), UPPER_TRIM);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(6);
        assertThat(result.conformingRows()).isEqualTo(2);
        assertThat(result.duplicateGroups()).isEqualTo(2L);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(2L, 3L, 5L, 6L);
        assertThat(anomalies).extracting(Anomaly::duplicateGroup).containsExactly(1L, 1L, 2L, 2L);
    }

    private static RuleDefinition rule(List<String> columns, Normalization normalization) {
        return Rules.rule("UNI-03", Dimension.UNIQUENESS, columns, Map.of(), normalization);
    }
}
