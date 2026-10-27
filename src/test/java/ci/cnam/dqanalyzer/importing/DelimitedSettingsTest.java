package ci.cnam.dqanalyzer.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** IMP-03 : les paramètres d'import sont limités aux encodages et séparateurs du CDC, et corrigeables. */
class DelimitedSettingsTest {

    @Test
    void normalise_le_nom_de_l_encodage() {
        var settings = new DelimitedSettings("utf-8", ';', '"', true);

        assertThat(settings.charsetName()).isEqualTo("UTF-8");
        assertThat(settings.charset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void accepte_windows_1252() {
        var settings = new DelimitedSettings("Windows-1252", ';', '"', true);

        assertThat(settings.charsetName()).isEqualTo("windows-1252");
        assertThat(settings.charset().name()).isEqualTo("windows-1252");
    }

    @Test
    void refuse_un_encodage_hors_liste() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> new DelimitedSettings("KOI8-R", ';', '"', true))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.encodage-inconnu");
    }

    @Test
    void refuse_un_separateur_hors_liste() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> new DelimitedSettings("UTF-8", '|', '"', true))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.separateur-inconnu");
    }

    @Test
    void refuse_un_guillemet_egal_au_separateur() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> new DelimitedSettings("UTF-8", ';', ';', true));
    }

    @Test
    void laisse_l_utilisateur_corriger_la_detection() {
        var detected = new DelimitedSettings("UTF-8", ',', '"', true);

        var corrected =
                detected.withDelimiter(';').withCharsetName("windows-1252").withHasHeader(false);

        assertThat(corrected.delimiter()).isEqualTo(';');
        assertThat(corrected.charsetName()).isEqualTo("windows-1252");
        assertThat(corrected.hasHeader()).isFalse();
        assertThat(detected.delimiter()).isEqualTo(',');
    }
}
