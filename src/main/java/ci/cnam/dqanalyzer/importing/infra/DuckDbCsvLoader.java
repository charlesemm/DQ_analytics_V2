package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ImportProgressListener;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.ImportedTable;
import ci.cnam.dqanalyzer.importing.domain.HeaderNames;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.duckdb.DuckDBConnection;
import org.springframework.stereotype.Component;

/**
 * Charge un fichier texte délimité dans une table DuckDB, enregistrement par enregistrement, par
 * l'appender DuckDB (PERF-03). À aucun moment le fichier, ni même un lot de lignes, n'est conservé en
 * mémoire : un fichier de 2 Go se charge avec la même empreinte qu'un fichier de 2 Ko.
 *
 * <p>La table reçoit {@code _row_num} (n° de ligne d'origine) puis les colonnes du fichier, toutes en
 * VARCHAR : les règles contrôlent le texte brut, aucune conversion de type n'est devinée à l'import.
 *
 * <p>Conventions, identiques à celles des jeux de test des évaluateurs :
 *
 * <ul>
 *   <li>avec en-tête, l'en-tête est la ligne 1 et la première donnée la ligne 2 ; sans en-tête, la
 *       première donnée est la ligne 1 ;
 *   <li>les lignes vides sont ignorées et ne consomment pas de numéro ;
 *   <li>une cellule vide devient une valeur absente (NULL) : dans un fichier texte, rien ne distingue
 *       « vide » de « non renseigné » ;
 *   <li>une ligne trop courte est complétée par des valeurs absentes, une ligne trop longue est
 *       tronquée ; ces lignes sont comptées et signalées à l'analyste.
 * </ul>
 */
@Component
public class DuckDbCsvLoader {

    /** L'appender regroupe les lignes ; on vide son tampon régulièrement pour suivre la progression. */
    private static final long FLUSH_EVERY_ROWS = 50_000L;

    private static final int READER_BUFFER = 64 * 1024;
    private static final int MAX_PERCENT_BEFORE_END = 99;
    private static final String MAIN_SCHEMA = "main";

    /** Les noms de table sont fabriqués par l'application, jamais saisis : on le vérifie ici. */
    private static final Pattern GENERATED_TABLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final DuckDbDatabases databases;

    public DuckDbCsvLoader(DuckDbDatabases databases) {
        this.databases = Objects.requireNonNull(databases, "databases");
    }

    public ImportedTable load(Path csvFile, ImportSettings settings, Path database, String tableName) {
        return load(csvFile, settings, database, tableName, ImportProgressListener.IGNORE);
    }

    /**
     * @param progressListener prévenu à chaque vidage du tampon de l'appender
     * @throws ImportRejectedException si le fichier est illisible ou ne contient aucune donnée
     */
    public ImportedTable load(
            Path csvFile,
            ImportSettings settings,
            Path database,
            String tableName,
            ImportProgressListener progressListener) {
        if (!GENERATED_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Nom de table non conforme : " + tableName);
        }
        // Un import écrit toujours une base neuve : un fichier qui porterait déjà ce nom est le
        // résidu d'un chargement abandonné, et sa table ferait échouer la création de la nôtre.
        deleteQuietly(database);
        try {
            return loadInto(csvFile, settings, database, tableName, progressListener);
        } catch (RuntimeException e) {
            deleteQuietly(database); // une base à moitié chargée ne doit pas rester sur le disque
            throw e;
        }
    }

    private ImportedTable loadInto(
            Path csvFile,
            ImportSettings settings,
            Path database,
            String tableName,
            ImportProgressListener progressListener) {
        try (var in = new CountingInputStream(Files.newInputStream(csvFile));
                var reader = new BufferedReader(new InputStreamReader(in, settings.charset()), READER_BUFFER);
                var parser = CSVParser.parse(reader, format(settings))) {

            // L'avancement se mesure en octets consommés du fichier : c'est la seule grandeur dont on
            // connaisse le total à l'avance, le nombre de lignes n'étant connu qu'à la fin.
            long fileSize = Files.size(csvFile);
            var records = parser.iterator();
            if (!hasNextRecord(records, parser)) {
                throw new ImportRejectedException("import.error.fichier-vide");
            }
            CSVRecord firstRecord = nextRecord(records, parser);
            List<String> columns;
            CSVRecord pendingRecord;
            if (settings.hasHeader()) {
                columns = HeaderNames.normalize(values(firstRecord));
                pendingRecord = null;
            } else {
                columns = HeaderNames.normalize(Collections.nCopies(firstRecord.size(), ""));
                pendingRecord = firstRecord;
            }

            try (var connection = databases.open(database)) {
                createTable(connection, tableName, columns);
                var loaded = new Append(connection, tableName, columns, progressListener, in, fileSize)
                        .run(pendingRecord, records, parser);
                if (loaded.rows() == 0) {
                    throw new ImportRejectedException("import.error.aucune-donnee");
                }
                return new ImportedTable(database, tableName, columns, loaded.rows(), loaded.raggedRows());
            }
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        } catch (SQLException e) {
            throw new IllegalStateException("Chargement impossible dans la table " + tableName, e);
        }
    }

    /** Écriture des lignes par l'appender : regroupe les paramètres communs à la boucle. */
    private static final class Append {

        private final Connection connection;
        private final String tableName;
        private final List<String> columns;
        private final ImportProgressListener progressListener;
        private final CountingInputStream input;
        private final long fileSize;

        private Append(
                Connection connection,
                String tableName,
                List<String> columns,
                ImportProgressListener progressListener,
                CountingInputStream input,
                long fileSize) {
            this.connection = connection;
            this.tableName = tableName;
            this.columns = columns;
            this.progressListener = progressListener;
            this.input = input;
            this.fileSize = fileSize;
        }

        private LoadedRows run(CSVRecord pendingRecord, Iterator<CSVRecord> records, CSVParser parser)
                throws SQLException {
            long rows = 0;
            long raggedRows = 0;
            try (var appender = connection.unwrap(DuckDBConnection.class).createAppender(MAIN_SCHEMA, tableName)) {
                CSVRecord record = pendingRecord;
                while (record != null || hasNextRecord(records, parser)) {
                    if (record == null) {
                        record = nextRecord(records, parser);
                    }
                    if (record.size() != columns.size()) {
                        raggedRows++;
                    }
                    appender.beginRow();
                    appender.append(record.getRecordNumber());
                    for (int column = 0; column < columns.size(); column++) {
                        String value = column < record.size() ? record.get(column) : "";
                        if (value == null || value.isEmpty()) {
                            appender.appendNull();
                        } else {
                            appender.append(value);
                        }
                    }
                    appender.endRow();
                    rows++;
                    if (rows % FLUSH_EVERY_ROWS == 0) {
                        appender.flush();
                        progressListener.progress(percent(), rows);
                    }
                    record = null;
                }
                appender.flush();
            }
            progressListener.progress(percent(), rows);
            return new LoadedRows(rows, raggedRows);
        }

        private int percent() {
            if (fileSize <= 0) {
                return MAX_PERCENT_BEFORE_END;
            }
            long done = input.bytesRead() * 100 / fileSize;
            return (int) Math.min(MAX_PERCENT_BEFORE_END, done);
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

    private static void createTable(Connection connection, String tableName, List<String> columns) throws SQLException {
        var ddl = new StringBuilder("CREATE TABLE ")
                .append(DuckDbIdentifiers.quote(tableName))
                .append(" (")
                .append(DuckDbIdentifiers.quote(Dataset.ROW_NUM_COLUMN))
                .append(" BIGINT");
        for (String column : columns) {
            ddl.append(", ").append(DuckDbIdentifiers.quote(column)).append(" VARCHAR");
        }
        try (var statement = connection.createStatement()) {
            statement.execute(ddl.append(')').toString());
        }
    }

    private static CSVFormat format(ImportSettings settings) {
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

    private static void deleteQuietly(Path database) {
        try {
            Files.deleteIfExists(database);
            Files.deleteIfExists(Path.of(database + ".wal"));
        } catch (IOException e) {
            // Rien à faire de plus : le fichier sera purgé avec le répertoire de travail.
        }
    }

    /** Compte les octets consommés du fichier, pour exprimer l'avancement en pourcentage. */
    private static final class CountingInputStream extends FilterInputStream {

        private long bytesRead;

        private CountingInputStream(InputStream in) {
            super(in);
        }

        private long bytesRead() {
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

    private record LoadedRows(long rows, long raggedRows) {}
}
