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
 * VAL-05 sur un sexe (M, F) et un statut accentué. Jeu de test : 7 lignes (n° de ligne 2 à 8),
 * une valeur hors liste, une en minuscules, une absente, une blanche et un statut accentué.
 */
class Val05AllowedValuesEvaluatorTest {

    private final Val05AllowedValuesEvaluator evaluator = new Val05AllowedValuesEvaluator();
    private Connection connection;
    private Dataset dataset;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        dataset = TestDatasets.loadCsv(connection, "datasets/val05_sexe.csv");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void declare_le_type_et_la_dimension_du_catalogue() {
        assertThat(evaluator.type()).isEqualTo("VAL-05");
        assertThat(evaluator.dimension()).isEqualTo(Dimension.VALIDITY);
        assertThat(evaluator.paramSchema().params()).extracting(p -> p.name()).containsExactly("values");
    }

    @Test
    void detecte_les_valeurs_hors_liste() {
        var rule = allowedRule("sexe", Map.of("values", List.of("M", "F")), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(5);
        assertThat(result.conformingRows()).isEqualTo(2);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(4L, 7L, 8L);
        assertThat(anomalies).extracting(Anomaly::value).containsExactly("X", "m", "Masculin");
        assertThat(anomalies).allSatisfy(a -> {
            assertThat(a.column()).isEqualTo("sexe");
            assertThat(a.message()).contains("sexe").contains("M").contains("F");
        });
    }

    @Test
    void valeurs_absentes_ou_blanches_hors_perimetre() {
        var rule = allowedRule("sexe", Map.of("values", List.of("M", "F")), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(anomalies).extracting(Anomaly::rowNum).doesNotContain(5L, 6L);
    }

    @Test
    void casse_ignoree_apres_normalisation() {
        var rule = allowedRule("sexe", Map.of("values", List.of("m", "f")), new Normalization(true, true, false));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(3);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(4L, 8L);
    }

    @Test
    void accents_et_casse_ignores_apres_normalisation() {
        var rule = allowedRule(
                "statut", Map.of("values", List.of("ACTIF", "INACTIF", "DECEDE")), new Normalization(true, true, true));
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(7);
        assertThat(result.anomalyRows()).isZero();
        assertThat(anomalies).isEmpty();
    }

    @Test
    void sans_normalisation_accents_et_casse_sont_des_anomalies() {
        var rule = allowedRule("statut", Map.of("values", List.of("ACTIF", "INACTIF", "DECEDE")), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(5);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(4L, 7L);
    }

    @Test
    void liste_saisie_en_texte_separe_par_des_virgules() {
        var rule = allowedRule("sexe", Map.of("values", "M, F"), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.conformingRows()).isEqualTo(2);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(4L, 7L, 8L);
    }

    @Test
    void valeur_autorisee_malveillante_reste_un_parametre_sql() {
        var rule = allowedRule("sexe", Map.of("values", List.of("M", "F') OR (1=1")), Normalization.NONE);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(5);
        assertThat(result.conformingRows()).isEqualTo(1);
        assertThat(anomalies).extracting(Anomaly::value).containsExactly("F", "X", "m", "Masculin");
    }

    @Test
    void liste_absente_message_clair() {
        var rule = allowedRule("sexe", Map.of(), Normalization.NONE);

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("values");
    }

    private static RuleDefinition allowedRule(String column, Map<String, Object> params, Normalization normalization) {
        return Rules.rule("VAL-05", Dimension.VALIDITY, List.of(column), params, normalization);
    }
}
