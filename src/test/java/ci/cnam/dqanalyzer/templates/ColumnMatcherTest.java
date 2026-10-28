package ci.cnam.dqanalyzer.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Association des colonnes attendues d'un modèle avec celles d'un fichier (MOD-02). */
class ColumnMatcherTest {

    @Test
    void associe_malgre_les_accents_la_casse_et_les_separateurs() {
        var mapping = ColumnMatcher.match(
                List.of("Date de naissance", "N° sécu"), List.of("DATE_DE_NAISSANCE", "n_secu", "ville"));

        assertThat(mapping)
                .containsEntry("Date de naissance", "DATE_DE_NAISSANCE")
                .containsEntry("N° sécu", "n_secu");
    }

    @Test
    void laisse_sans_correspondance_une_colonne_absente_du_fichier() {
        var mapping = ColumnMatcher.match(List.of("Commune"), List.of("num"));

        assertThat(mapping).containsEntry("Commune", null);
    }

    @Test
    void garde_l_ordre_des_colonnes_attendues_pour_l_ecran_de_correction() {
        var mapping = ColumnMatcher.match(List.of("Nom", "Prenom", "Ville"), List.of("ville", "nom"));

        assertThat(mapping.keySet()).containsExactly("Nom", "Prenom", "Ville");
        assertThat(mapping.get("Prenom")).isNull();
    }

    @Test
    void retient_la_premiere_colonne_du_fichier_en_cas_d_ambiguite() {
        var mapping = ColumnMatcher.match(List.of("Nom"), List.of("NOM", "nom"));

        assertThat(mapping).containsEntry("Nom", "NOM");
    }
}
