package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.DelimitedSettings;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Lecture d'un fichier texte délimité (.csv, .tsv, .txt) en flux : séparateur, guillemets, encodage
 * et présence d'un en-tête viennent des paramètres retenus par l'analyste (IMP-03).
 *
 * <p>Les lignes vides sont ignorées et ne consomment pas de numéro de ligne, conformément aux jeux de
 * test des évaluateurs. L'avancement est mesuré en octets consommés du fichier : c'est la seule
 * grandeur dont le total soit connu à l'avance, le nombre de lignes ne l'étant qu'à la fin.
 */
public final class DelimitedRowSource implements RowSource {

    private static final int READER_BUFFER = 64 * 1024;

    private final CountingInputStream input;
    private final BufferedReader reader;
    private final CSVParser parser;
    private final Iterator<CSVRecord> records;
    private final List<String> columns;
    private final long fileSize;

    /** Première ligne de données, déjà lue pour en déduire les colonnes d'un fichier sans en-tête. */
    private CSVRecord pending;

    private CSVRecord current;

    private DelimitedRowSource(
            CountingInputStream input,
            BufferedReader reader,
            CSVParser parser,
            Iterator<CSVRecord> records,
            List<String> columns,
            CSVRecord pending,
            long fileSize) {
        this.input = input;
        this.reader = reader;
        this.parser = parser;
        this.records = records;
        this.columns = columns;
        this.pending = pending;
        this.fileSize = fileSize;
    }

    /**
     * Ouvre le fichier et lit sa première ligne, de laquelle sortent les noms de colonnes.
     *
     * @throws ImportRejectedException si le fichier est illisible ou ne contient aucune ligne
     */
    public static DelimitedRowSource open(Path file, DelimitedSettings settings) {
        CountingInputStream input = null;
        BufferedReader reader = null;
        CSVParser parser = null;
        try {
            long fileSize = Files.size(file);
            input = new CountingInputStream(Files.newInputStream(file));
            reader = new BufferedReader(new InputStreamReader(input, settings.charset()), READER_BUFFER);
            parser = CSVParser.parse(reader, format(settings));
            var records = parser.iterator();
            if (!hasNextRecord(records, parser)) {
                throw new ImportRejectedException("import.error.fichier-vide");
            }
            CSVRecord firstRecord = nextRecord(records, parser);
            List<String> columns;
            CSVRecord pending;
            if (settings.hasHeader()) {
                columns = HeaderNames.normalize(values(firstRecord));
                pending = null;
            } else {
                columns = HeaderNames.normalize(Collections.nCopies(firstRecord.size(), ""));
                pending = firstRecord;
            }
            return new DelimitedRowSource(input, reader, parser, records, columns, pending, fileSize);
        } catch (IOException e) {
            closeQuietly(parser, reader, input);
            throw new ImportRejectedException("import.error.fichier-illisible");
        } catch (RuntimeException e) {
            closeQuietly(parser, reader, input);
            throw e;
        }
    }

    @Override
    public List<String> columns() {
        return columns;
    }

    @Override
    public boolean next() {
        if (pending != null) {
            current = pending;
            pending = null;
            return true;
        }
        if (!hasNextRecord(records, parser)) {
            current = null;
            return false;
        }
        current = nextRecord(records, parser);
        return true;
    }

    @Override
    public long rowNumber() {
        return current.getRecordNumber();
    }

    @Override
    public int fieldCount() {
        return current.size();
    }

    @Override
    public String field(int index) {
        if (index >= current.size()) {
            return null;
        }
        String value = current.get(index);
        return value == null || value.isEmpty() ? null : value;
    }

    @Override
    public int percent() {
        if (fileSize <= 0) {
            return MAX_PERCENT_BEFORE_END;
        }
        long done = input.bytesRead() * 100 / fileSize;
        return (int) Math.min(MAX_PERCENT_BEFORE_END, done);
    }

    @Override
    public void close() throws IOException {
        try (input;
                reader;
                parser) {
            // Les trois flux se ferment ensemble, même si l'un d'eux échoue.
        }
    }

    /**
     * Une ligne mal formée (guillemet non fermé ou en trop) interrompt la lecture : on dit laquelle.
     * Commons CSV lit en avance, l'erreur peut donc tomber aussi bien sur {@code hasNext} que sur
     * {@code next} : les deux passent par ici.
     */
    private static boolean hasNextRecord(Iterator<CSVRecord> records, CSVParser parser) {
        try {
            return records.hasNext();
        } catch (UncheckedIOException | IllegalStateException e) {
            throw unreadableLine(parser);
        }
    }

    private static CSVRecord nextRecord(Iterator<CSVRecord> records, CSVParser parser) {
        try {
            return records.next();
        } catch (UncheckedIOException | IllegalStateException e) {
            throw unreadableLine(parser);
        }
    }

    private static ImportRejectedException unreadableLine(CSVParser parser) {
        return new ImportRejectedException("import.error.ligne-illisible", parser.getCurrentLineNumber());
    }

    private static CSVFormat format(DelimitedSettings settings) {
        return CSVFormat.DEFAULT
                .builder()
                .setDelimiter(settings.delimiter())
                .setQuote(settings.quote())
                .setIgnoreEmptyLines(true)
                .get();
    }

    private static List<String> values(CSVRecord record) {
        var values = new ArrayList<String>(record.size());
        for (String value : record) {
            values.add(value);
        }
        return values;
    }

    private static void closeQuietly(Closeable... streams) {
        for (Closeable stream : streams) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // Ouverture déjà en échec : rien de plus à tenter sur ce flux.
                }
            }
        }
    }
}
