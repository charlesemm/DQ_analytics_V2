package ci.cnam.dqanalyzer.importing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * IMP-07 : détection de l'encodage et du séparateur. La détection ne lit qu'un échantillon du
 * fichier, jamais la totalité (PERF-03).
 */
class TextFormatDetectorTest {

    private final TextFormatDetector detector = new TextFormatDetector();

    @Test
    void detecte_utf8_et_la_virgule() {
        var settings = detector.detect(fixture("utf8_virgule.csv"));

        assertThat(settings.charsetName()).isEqualTo("UTF-8");
        assertThat(settings.delimiter()).isEqualTo(',');
        assertThat(settings.hasHeader()).isTrue();
    }

    @Test
    void detecte_windows_1252_et_le_point_virgule() {
        var settings = detector.detect(fixture("win1252_pointvirgule.csv"));

        assertThat(settings.charsetName()).isEqualTo("windows-1252");
        assertThat(settings.delimiter()).isEqualTo(';');
    }

    @Test
    void detecte_la_tabulation() {
        assertThat(detector.detect(fixture("tabule.tsv")).delimiter()).isEqualTo('\t');
    }

    @Test
    void ignore_le_bom_utf8() {
        var settings = detector.detect(fixture("utf8_bom_pointvirgule.csv"));

        assertThat(settings.charsetName()).isEqualTo("UTF-8");
        assertThat(settings.delimiter()).isEqualTo(';');
    }

    @Test
    void ne_se_laisse_pas_tromper_par_une_virgule_dans_un_champ_entre_guillemets() {
        assertThat(detector.detect(fixture("guillemets_pointvirgule.csv")).delimiter())
                .isEqualTo(';');
    }

    @Test
    void refuse_un_fichier_vide() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> detector.detect(fixture("vide.csv")))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.fichier-vide");
    }

    @Test
    void refuse_un_fichier_reduit_a_son_entete() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> detector.detect(fixture("entete_seule.csv")))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.aucune-donnee");
    }

    @Test
    void refuse_un_fichier_sans_separateur_reconnaissable() {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> detector.detect(fixture("une_colonne.csv")))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.separateur-indetectable");
    }

    @Test
    void ne_lit_qu_un_echantillon_du_fichier(@TempDir Path directory) throws IOException {
        Path large = directory.resolve("gros.csv");
        try (var writer = Files.newBufferedWriter(large, StandardCharsets.UTF_8)) {
            writer.write("id;nom;ville\n");
            for (int i = 0; i < 100_000; i++) {
                writer.write(i + ";nom" + i + ";Abidjan\n");
            }
        }
        assertThat(Files.size(large)).isGreaterThan(4L * TextFormatDetector.SAMPLE_SIZE);

        try (var counting = new CountingInputStream(Files.newInputStream(large))) {
            assertThat(detector.detect(counting).delimiter()).isEqualTo(';');
            assertThat(counting.bytesRead()).isLessThanOrEqualTo(TextFormatDetector.SAMPLE_SIZE);
        }
    }

    private static Path fixture(String name) {
        var url = Objects.requireNonNull(
                TextFormatDetectorTest.class.getClassLoader().getResource("imports/" + name), "fixture " + name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compte les octets réellement lus : c'est la preuve que le fichier n'est pas chargé en entier. */
    private static final class CountingInputStream extends FilterInputStream {

        private long bytesRead;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        long bytesRead() {
            return bytesRead;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }
    }
}
