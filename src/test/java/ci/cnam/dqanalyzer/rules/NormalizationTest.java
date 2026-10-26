package ci.cnam.dqanalyzer.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalizationTest {

    @Test
    void sans_normalisation_simple_conversion_en_texte() {
        assertThat(Normalization.NONE.apply("\"nom\"")).isEqualTo("CAST(\"nom\" AS VARCHAR)");
    }

    @Test
    void toutes_les_options_dans_l_ordre_espaces_accents_majuscules() {
        var all = new Normalization(true, true, true);

        assertThat(all.apply("\"nom\"")).isEqualTo("upper(strip_accents(trim(CAST(\"nom\" AS VARCHAR))))");
    }
}
