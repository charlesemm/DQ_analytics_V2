package ci.cnam.dqanalyzer.templates;

import static org.assertj.core.api.Assertions.assertThat;

import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.testsupport.Rules;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Adaptation des colonnes d'une règle au fichier analysé (MOD-03). */
class TemplateRulesTest {

    private static final Map<String, String> MAPPING =
            Map.of("Date de naissance", "DATE_DE_NAISSANCE", "Statut", "statut_assure");

    @Test
    void remplace_les_colonnes_ciblees_par_celles_du_fichier() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("Date de naissance"), Map.of());

        var remapped = TemplateRules.remap(List.of(rule), MAPPING);

        assertThat(remapped)
                .singleElement()
                .extracting(RuleDefinition::targetColumns)
                .isEqualTo(List.of("DATE_DE_NAISSANCE"));
    }

    @Test
    void laisse_telle_quelle_une_colonne_absente_de_l_association() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("ville"), Map.of());

        var remapped = TemplateRules.remap(List.of(rule), MAPPING);

        assertThat(remapped.getFirst().targetColumns()).containsExactly("ville");
        assertThat(remapped.getFirst()).isSameAs(rule);
    }

    @Test
    void traduit_aussi_les_colonnes_citees_dans_une_condition() {
        var rule = Rules.rule(
                "CMP-03",
                Dimension.COMPLETENESS,
                List.of("Date de naissance"),
                Map.of("when", Map.of("all", List.of(Map.of("column", "Statut", "op", "eq", "value", "AD")))));

        var remapped = TemplateRules.remap(List.of(rule), MAPPING);

        @SuppressWarnings("unchecked")
        var when = (Map<String, Object>) remapped.getFirst().params().get("when");
        @SuppressWarnings("unchecked")
        var conditions = (List<Map<String, Object>>) when.get("all");
        assertThat(conditions.getFirst())
                .containsEntry("column", "statut_assure")
                .containsEntry("op", "eq");
    }

    @Test
    void sans_association_les_regles_sont_rendues_telles_quelles() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("Date de naissance"), Map.of());

        assertThat(TemplateRules.remap(List.of(rule), Map.of())).containsExactly(rule);
    }
}
