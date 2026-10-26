package ci.cnam.dqanalyzer.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import ci.cnam.dqanalyzer.execution.RuleStatus;
import ci.cnam.dqanalyzer.execution.domain.Scoring.ScoredRule;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleResult;
import ci.cnam.dqanalyzer.rules.Severity;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** Vérifie le calcul du CDC §7 sur des valeurs calculées à la main. */
class ScoringTest {

    private final Scoring scoring = new Scoring();

    private final List<ScoredRule> rules = List.of(
            new ScoredRule(Dimension.COMPLETENESS, Severity.BLOCKING, List.of("a"), OptionalDouble.of(100)),
            new ScoredRule(Dimension.COMPLETENESS, Severity.MINOR, List.of("b"), OptionalDouble.of(40)),
            new ScoredRule(Dimension.VALIDITY, Severity.MAJOR, List.of("a"), OptionalDouble.of(90)),
            new ScoredRule(Dimension.VALIDITY, Severity.MAJOR, List.of("a"), OptionalDouble.empty()));

    @Test
    void score_regle_conformes_sur_controlees() {
        assertThat(scoring.ruleScore(RuleResult.of(6, 3)).getAsDouble()).isEqualTo(50.0);
    }

    @Test
    void regle_sans_ligne_controlee_sans_score() {
        assertThat(scoring.ruleScore(RuleResult.of(0, 0))).isEmpty();
    }

    @Test
    void statut_regle_selon_seuil_et_severite() {
        assertThat(scoring.ruleStatus(OptionalDouble.of(80), 80, Severity.BLOCKING))
                .isEqualTo(RuleStatus.OK);
        assertThat(scoring.ruleStatus(OptionalDouble.of(50), 80, Severity.BLOCKING))
                .isEqualTo(RuleStatus.KO);
        assertThat(scoring.ruleStatus(OptionalDouble.of(50), 80, Severity.MAJOR))
                .isEqualTo(RuleStatus.ALERT);
        assertThat(scoring.ruleStatus(OptionalDouble.of(50), 80, Severity.MINOR))
                .isEqualTo(RuleStatus.ALERT);
        assertThat(scoring.ruleStatus(OptionalDouble.empty(), 80, Severity.BLOCKING))
                .isEqualTo(RuleStatus.OK);
    }

    @Test
    void score_dimension_pondere_par_severite() {
        var scores = scoring.dimensionScores(rules);

        // Complétude : (3×100 + 1×40) / (3+1) = 85 ; Validité : la règle sans score est ignorée → 90
        assertThat(scores).containsEntry(Dimension.COMPLETENESS, 85.0).containsEntry(Dimension.VALIDITY, 90.0);
        assertThat(scores).doesNotContainKey(Dimension.UNIQUENESS);
    }

    @Test
    void score_global_moyenne_des_dimensions_mesurees() {
        var dims = scoring.dimensionScores(rules);

        assertThat(scoring.globalScore(dims, Map.of()).getAsDouble()).isEqualTo(87.5);
        // Validité pondérée 3 : (85 + 3×90) / 4 = 88,75
        assertThat(scoring.globalScore(dims, Map.of(Dimension.VALIDITY, 3.0)).getAsDouble())
                .isCloseTo(88.75, within(1e-9));
        assertThat(scoring.globalScore(Map.of(), Map.of())).isEmpty();
    }

    @Test
    void score_colonne_restreint_aux_regles_de_la_colonne() {
        // Colonne a : (3×100 + 2×90) / (3+2) = 96
        assertThat(scoring.columnScore("a", rules).getAsDouble()).isEqualTo(96.0);
        assertThat(scoring.columnScore("z", rules)).isEmpty();
    }

    @Test
    void statut_global_ko_des_qu_une_bloquante_est_ko() {
        assertThat(scoring.globalStatus(List.of(RuleStatus.OK, RuleStatus.ALERT, RuleStatus.KO)))
                .isEqualTo(RuleStatus.KO);
        assertThat(scoring.globalStatus(List.of(RuleStatus.OK, RuleStatus.ALERT)))
                .isEqualTo(RuleStatus.ALERT);
        assertThat(scoring.globalStatus(List.of(RuleStatus.OK))).isEqualTo(RuleStatus.OK);
    }

    @Test
    void poids_de_severite_personnalises() {
        var custom = new Scoring(Map.of(Severity.BLOCKING, 1, Severity.MAJOR, 1, Severity.MINOR, 1));

        // (100 + 40) / 2 = 70
        assertThat(custom.dimensionScores(rules)).containsEntry(Dimension.COMPLETENESS, 70.0);
    }
}
