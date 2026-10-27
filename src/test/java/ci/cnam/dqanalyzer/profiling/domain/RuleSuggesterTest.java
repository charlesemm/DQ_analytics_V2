package ci.cnam.dqanalyzer.profiling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ci.cnam.dqanalyzer.profiling.ColumnProfile;
import ci.cnam.dqanalyzer.profiling.domain.RuleSuggester.Suggestion;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Suggestions de règles (PRF-04). Le suggesteur ne fait aucune requête : c'est une fonction du profil
 * vers une liste de suggestions, donc il se teste sans DuckDB, sur des profils construits à la main.
 */
class RuleSuggesterTest {

    private final RuleSuggester suggester = new RuleSuggester();

    @Test
    void propose_la_completude_quand_la_colonne_est_toujours_renseignee() {
        var profile = profile("ville", 100, 100, 4, 3, 7, "TEXTE");

        assertThat(types(profile)).contains("CMP-01");
    }

    @Test
    void ne_propose_pas_la_completude_quand_des_cellules_sont_absentes() {
        var profile = profile("ville", 100, 98, 4, 3, 7, "TEXTE");

        assertThat(types(profile)).doesNotContain("CMP-01");
    }

    @Test
    void propose_l_unicite_quand_toutes_les_valeurs_sont_differentes() {
        var profile = profile("matricule", 22, 22, 22, 8, 8, "TEXTE");

        assertThat(types(profile)).contains("UNI-01");
    }

    @Test
    void ne_propose_pas_l_unicite_sur_une_colonne_a_une_seule_valeur() {
        var profile = profile("matricule", 1, 1, 1, 8, 8, "TEXTE");

        assertThat(types(profile)).doesNotContain("UNI-01");
    }

    @Test
    void propose_un_controle_de_type_selon_le_type_detecte() {
        assertThat(types(profile("age", 50, 50, 40, 1, 2, "ENTIER"))).contains("VAL-01");
        assertThat(types(profile("salaire", 50, 50, 40, 4, 8, "DECIMAL"))).contains("VAL-01");
        assertThat(types(profile("naissance", 50, 50, 40, 10, 10, "DATE"))).contains("VAL-06");
    }

    @Test
    void propose_une_liste_fermee_quand_une_colonne_texte_a_peu_de_valeurs_differentes() {
        assertThat(types(profile("sexe", 22, 22, 2, 1, 1, "TEXTE"))).contains("VAL-05");
        // Sous 20 valeurs remplies, la liste observée ne prouve rien : pas de suggestion.
        assertThat(types(profile("sexe", 19, 19, 2, 1, 1, "TEXTE"))).doesNotContain("VAL-05");
        assertThat(types(profile("ville", 100, 100, 40, 3, 12, "TEXTE"))).doesNotContain("VAL-05");
    }

    @Test
    void propose_une_longueur_fixe_avec_la_longueur_dans_le_motif() {
        var suggestions = suggester.suggest(List.of(profile("matricule", 22, 22, 22, 8, 8, "TEXTE")));

        assertThat(suggestions)
                .extracting(Suggestion::ruleType, Suggestion::reason)
                .contains(tuple("VAL-03", "Toutes les valeurs font 8 caractères"));
    }

    @Test
    void ne_propose_pas_de_longueur_fixe_sur_des_valeurs_d_un_seul_caractere() {
        assertThat(types(profile("sexe", 22, 22, 2, 1, 1, "TEXTE"))).doesNotContain("VAL-03");
        assertThat(types(profile("ville", 22, 22, 4, 3, 7, "TEXTE"))).doesNotContain("VAL-03");
    }

    @Test
    void ne_propose_rien_sur_une_colonne_entierement_vide() {
        var profile = new ColumnProfile("commentaire", 22, 0, 0, null, null, null, null, "VIDE", List.of(), List.of());

        assertThat(suggester.suggest(List.of(profile))).isEmpty();
    }

    @Test
    void nomme_la_colonne_de_chaque_suggestion() {
        var suggestions = suggester.suggest(
                List.of(profile("matricule", 22, 22, 22, 8, 8, "TEXTE"), profile("age", 22, 22, 18, 1, 2, "ENTIER")));

        assertThat(suggestions).extracting(Suggestion::column).contains("matricule", "age");
    }

    private List<String> types(ColumnProfile profile) {
        return suggester.suggest(List.of(profile)).stream()
                .map(Suggestion::ruleType)
                .toList();
    }

    private static ColumnProfile profile(
            String column,
            long rowCount,
            long filled,
            long distinctValues,
            Integer minLength,
            Integer maxLength,
            String detectedType) {
        return new ColumnProfile(
                column,
                rowCount,
                filled,
                distinctValues,
                minLength,
                maxLength,
                "min",
                "max",
                detectedType,
                List.of(),
                List.of());
    }
}
