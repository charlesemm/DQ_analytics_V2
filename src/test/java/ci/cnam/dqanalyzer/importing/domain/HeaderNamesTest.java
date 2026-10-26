package ci.cnam.dqanalyzer.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Noms de colonnes d'un fichier réel : en-têtes vides, doublons, espaces. Les noms doivent rester
 * utilisables comme colonnes DuckDB, qui ne distingue pas la casse des identifiants.
 */
class HeaderNamesTest {

    @Test
    void garde_les_noms_valides() {
        assertThat(HeaderNames.normalize(List.of("id", "nom", "ville"))).containsExactly("id", "nom", "ville");
    }

    @Test
    void supprime_les_espaces_autour() {
        assertThat(HeaderNames.normalize(List.of(" id ", "nom\t"))).containsExactly("id", "nom");
    }

    @Test
    void nomme_les_entetes_vides_par_leur_position() {
        assertThat(HeaderNames.normalize(List.of("id", "", "   ", "ville")))
                .containsExactly("id", "colonne_2", "colonne_3", "ville");
    }

    @Test
    void rend_les_doublons_uniques() {
        assertThat(HeaderNames.normalize(List.of("nom", "nom", "nom"))).containsExactly("nom", "nom_2", "nom_3");
    }

    @Test
    void rend_les_doublons_uniques_sans_tenir_compte_de_la_casse() {
        assertThat(HeaderNames.normalize(List.of("Nom", "nom"))).containsExactly("Nom", "nom_2");
    }

    @Test
    void evite_la_collision_avec_un_nom_deja_present() {
        assertThat(HeaderNames.normalize(List.of("nom", "nom", "nom_2"))).containsExactly("nom", "nom_2", "nom_2_2");
    }

    @Test
    void evite_la_collision_avec_la_colonne_technique_du_numero_de_ligne() {
        assertThat(HeaderNames.normalize(List.of("_row_num", "nom"))).containsExactly("_row_num_2", "nom");
    }

    @Test
    void refuse_un_fichier_sans_aucune_colonne() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> HeaderNames.normalize(List.of()))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.entete-vide");
    }
}
