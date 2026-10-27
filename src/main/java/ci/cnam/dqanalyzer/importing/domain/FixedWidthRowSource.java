package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.FixedWidthField;
import ci.cnam.dqanalyzer.importing.FixedWidthSettings;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture d'un fichier à largeur fixe : chaque ligne est découpée aux positions du gabarit (IMP-08).
 * Ces fichiers viennent d'extracteurs anciens, souvent des traitements par lots : ils n'ont ni
 * séparateur ni structure, le gabarit est la seule façon de savoir où commence une colonne.
 *
 * <p>Le numéro de ligne est celui du fichier. Une ligne entièrement blanche est ignorée, mais elle
 * garde son numéro : l'analyste doit pouvoir compter les lignes dans son éditeur et retrouver
 * exactement celle qu'on lui signale.
 *
 * <p>Les valeurs sont débarrassées des blancs de remplissage : ces fichiers complètent les colonnes
 * à l'espace, et « KONE      » est le même nom que « KONE ».
 */
public final class FixedWidthRowSource implements RowSource {

    private static final int READER_BUFFER = 64 * 1024;

    private final CountingInputStream input;
    private final BufferedReader reader;
    private final List<FixedWidthField> fields;
    private final List<String> columns;
    private final long fileSize;

    private long lineNumber;
    private String currentLine;
    private long currentNumber;

    private FixedWidthRowSource(
            CountingInputStream input,
            BufferedReader reader,
            List<FixedWidthField> fields,
            List<String> columns,
            long fileSize,
            long lineNumber) {
        this.input = input;
        this.reader = reader;
        this.fields = fields;
        this.columns = columns;
        this.fileSize = fileSize;
        this.lineNumber = lineNumber;
    }

    /**
     * Ouvre le fichier et nomme ses colonnes : le nom donné dans le gabarit, sinon celui lu dans la
     * ligne d'en-tête, sinon « colonne_n ».
     *
     * @throws ImportRejectedException si le gabarit est vide ou le fichier illisible
     */
    public static FixedWidthRowSource open(Path file, FixedWidthSettings settings) {
        if (settings.fields().isEmpty()) {
            throw new ImportRejectedException("import.error.gabarit-absent");
        }
        CountingInputStream input = null;
        BufferedReader reader = null;
        try {
            long fileSize = Files.size(file);
            input = new CountingInputStream(Files.newInputStream(file));
            reader = new BufferedReader(new InputStreamReader(input, settings.charset()), READER_BUFFER);
            String headerLine = settings.hasHeader() ? reader.readLine() : null;
            if (settings.hasHeader() && headerLine == null) {
                throw new ImportRejectedException("import.error.fichier-vide");
            }
            var columns = columns(settings.fields(), headerLine);
            return new FixedWidthRowSource(
                    input, reader, settings.fields(), columns, fileSize, settings.hasHeader() ? 1 : 0);
        } catch (IOException e) {
            closeQuietly(reader, input);
            throw new ImportRejectedException("import.error.fichier-illisible");
        } catch (RuntimeException e) {
            closeQuietly(reader, input);
            throw e;
        }
    }

    @Override
    public List<String> columns() {
        return columns;
    }

    @Override
    public boolean next() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.isBlank()) {
                    currentLine = line;
                    currentNumber = lineNumber;
                    return true;
                }
            }
            currentLine = null;
            return false;
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.ligne-illisible", lineNumber + 1);
        }
    }

    @Override
    public long rowNumber() {
        return currentNumber;
    }

    @Override
    public int fieldCount() {
        // Une ligne trop courte n'atteint pas toutes les colonnes du gabarit : elle est signalée
        // comme incomplète, ce qui est en général le signe d'un gabarit qui ne correspond pas.
        int reached = 0;
        for (FixedWidthField field : fields) {
            if (currentLine.length() >= field.start()) {
                reached++;
            }
        }
        return reached;
    }

    @Override
    public String field(int index) {
        return slice(currentLine, fields.get(index));
    }

    @Override
    public int percent() {
        if (fileSize <= 0) {
            return MAX_PERCENT_BEFORE_END;
        }
        return (int) Math.min(MAX_PERCENT_BEFORE_END, input.bytesRead() * 100 / fileSize);
    }

    @Override
    public void close() throws IOException {
        try (input;
                reader) {
            // Les deux flux se ferment ensemble, même si l'un d'eux échoue.
        }
    }

    /** Nom de chaque colonne : celui du gabarit, sinon celui de l'en-tête, sinon « colonne_n ». */
    private static List<String> columns(List<FixedWidthField> fields, String headerLine) {
        var names = new ArrayList<String>(fields.size());
        for (FixedWidthField field : fields) {
            if (!field.name().isEmpty()) {
                names.add(field.name());
            } else {
                String fromHeader = headerLine == null ? null : slice(headerLine, field);
                names.add(fromHeader == null ? "" : fromHeader);
            }
        }
        return HeaderNames.normalize(names);
    }

    /** Portion de la ligne décrite par la colonne, sans ses blancs de remplissage. */
    private static String slice(String line, FixedWidthField field) {
        int from = field.start() - 1;
        if (from >= line.length()) {
            return null;
        }
        String value =
                line.substring(from, Math.min(field.end(), line.length())).trim();
        return value.isEmpty() ? null : value;
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
