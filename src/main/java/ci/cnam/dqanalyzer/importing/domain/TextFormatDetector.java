package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.DelimitedSettings;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

/**
 * Détection de l'encodage et du séparateur d'un fichier texte délimité (IMP-07). Le résultat est une
 * proposition : l'analyste peut la corriger (IMP-03).
 *
 * <p>La détection ne lit que les {@value #SAMPLE_SIZE} premiers octets, jamais le fichier entier
 * (PERF-03) : un fichier de 2 Go se détecte au même prix qu'un fichier de 2 Ko.
 *
 * <p>Encodage : marque d'ordre d'octets si elle existe, sinon l'échantillon est décodé en UTF-8
 * strict ; s'il contient une séquence impossible en UTF-8, le fichier est lu en Windows-1252, qui
 * accepte tous les octets. Séparateur : on essaie chaque candidat du CDC et on garde celui qui donne
 * le nombre de colonnes le plus régulier.
 */
public final class TextFormatDetector {

    static final int SAMPLE_SIZE = 64 * 1024;

    private static final int SAMPLE_RECORDS = 10;
    private static final char QUOTE = '"';
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    /** Détecte les paramètres d'un fichier déposé sur le disque. */
    public DelimitedSettings detect(Path file) {
        try (var in = Files.newInputStream(file)) {
            return detect(in);
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    /**
     * Détecte le seul encodage, pour les formats texte qui n'ont pas de séparateur : largeur fixe,
     * XML, JSON (IMP-07).
     */
    public String detectCharsetName(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] sample = readSample(in);
            if (sample.length == 0) {
                throw new ImportRejectedException("import.error.fichier-vide");
            }
            return detectEncoding(sample, sample.length == SAMPLE_SIZE).charsetName();
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    /** Lit au plus {@value #SAMPLE_SIZE} octets du flux, qu'il ne ferme pas. */
    public DelimitedSettings detect(InputStream in) {
        byte[] sample = readSample(in);
        if (sample.length == 0) {
            throw new ImportRejectedException("import.error.fichier-vide");
        }
        boolean truncated = sample.length == SAMPLE_SIZE;
        var encoding = detectEncoding(sample, truncated);
        String text =
                new String(sample, encoding.bomLength(), sample.length - encoding.bomLength(), encoding.charset());
        return new DelimitedSettings(encoding.charsetName(), detectDelimiter(text, truncated), QUOTE, true);
    }

    private static byte[] readSample(InputStream in) {
        try {
            return in.readNBytes(SAMPLE_SIZE);
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    private static Encoding detectEncoding(byte[] sample, boolean truncated) {
        if (startsWith(sample, UTF8_BOM)) {
            return new Encoding("UTF-8", UTF8_BOM.length);
        }
        if (startsWith(sample, UTF16LE_BOM)) {
            return new Encoding("UTF-16LE", UTF16LE_BOM.length);
        }
        if (startsWith(sample, UTF16BE_BOM)) {
            return new Encoding("UTF-16BE", UTF16BE_BOM.length);
        }
        return new Encoding(isUtf8(sample, truncated) ? "UTF-8" : "windows-1252", 0);
    }

    private static boolean isUtf8(byte[] sample, boolean truncated) {
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // Échantillon coupé : on annonce « ce n'est pas la fin du flux », sinon un caractère accentué
        // coupé en deux par la fin de l'échantillon ferait conclure à tort que le fichier n'est pas UTF-8.
        return !decoder.decode(ByteBuffer.wrap(sample), CharBuffer.allocate(sample.length), !truncated)
                .isError();
    }

    /** Garde le candidat qui donne le nombre de colonnes le plus régulier sur l'échantillon. */
    private static char detectDelimiter(String text, boolean truncated) {
        char best = 0;
        int bestRegularRecords = -1;
        int bestColumns = 0;
        int bestRecords = 0;
        for (char candidate : DelimitedSettings.SUPPORTED_DELIMITERS) {
            var columnCounts = columnCounts(text, candidate, truncated);
            if (columnCounts.isEmpty() || columnCounts.get(0) < 2) {
                continue;
            }
            int columns = columnCounts.get(0);
            int regular = (int) columnCounts.stream()
                    .skip(1)
                    .filter(count -> count == columns)
                    .count();
            if (regular > bestRegularRecords || (regular == bestRegularRecords && columns > bestColumns)) {
                best = candidate;
                bestRegularRecords = regular;
                bestColumns = columns;
                bestRecords = columnCounts.size();
            }
        }
        if (best == 0) {
            throw new ImportRejectedException("import.error.separateur-indetectable");
        }
        if (bestRecords < 2 && !truncated) {
            throw new ImportRejectedException("import.error.aucune-donnee");
        }
        return best;
    }

    /** Nombre de colonnes des premiers enregistrements lus avec ce séparateur. */
    private static List<Integer> columnCounts(String text, char delimiter, boolean truncated) {
        var format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setQuote(QUOTE)
                .setIgnoreEmptyLines(true)
                .get();
        var counts = new ArrayList<Integer>();
        try (var parser = CSVParser.parse(text, format)) {
            for (var record : parser) {
                counts.add(record.size());
                if (counts.size() > SAMPLE_RECORDS) {
                    break;
                }
            }
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            // Échantillon coupé au milieu d'un champ entre guillemets : on garde ce qui a été lu.
        }
        if (truncated && !counts.isEmpty()) {
            counts.remove(counts.size() - 1); // le dernier enregistrement est probablement incomplet
        }
        return counts;
    }

    private static boolean startsWith(byte[] sample, byte[] prefix) {
        if (sample.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (sample[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private record Encoding(String charsetName, int bomLength) {

        Charset charset() {
            return Charset.forName(charsetName);
        }
    }
}
