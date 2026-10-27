package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ImportProgressListener;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.ImportedTable;
import ci.cnam.dqanalyzer.importing.ParquetSettings;
import ci.cnam.dqanalyzer.importing.domain.RowSource;
import ci.cnam.dqanalyzer.importing.domain.RowSources;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.duckdb.DuckDBConnection;
import org.springframework.stereotype.Component;

/**
 * Charge un fichier importé dans une table DuckDB, ligne par ligne, par l'appender DuckDB (PERF-03).
 * Le format est l'affaire du {@link RowSource} qu'on lui donne ; cette classe ne sait qu'écrire. À
 * aucun moment le fichier, ni même un lot de lignes, n'est conservé en mémoire : un fichier de 2 Go
 * se charge avec la même empreinte qu'un fichier de 2 Ko.
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
 *   <li>une cellule vide devient une valeur absente (NULL) : dans un fichier, rien ne distingue
 *       « vide » de « non renseigné » ;
 *   <li>une ligne trop courte est complétée par des valeurs absentes, une ligne trop longue est
 *       tronquée ; ces lignes sont comptées et signalées à l'analyste.
 * </ul>
 */
@Component
public class DuckDbTableLoader {

    /** L'appender regroupe les lignes ; on vide son tampon régulièrement pour suivre la progression. */
    private static final long FLUSH_EVERY_ROWS = 50_000L;

    private static final String MAIN_SCHEMA = "main";

    /** Les noms de table sont fabriqués par l'application, jamais saisis : on le vérifie ici. */
    private static final Pattern GENERATED_TABLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final DuckDbDatabases databases;
    private final DuckDbParquetLoader parquetLoader;

    DuckDbTableLoader(DuckDbDatabases databases, DuckDbParquetLoader parquetLoader) {
        this.databases = Objects.requireNonNull(databases, "databases");
        this.parquetLoader = Objects.requireNonNull(parquetLoader, "parquetLoader");
    }

    public ImportedTable load(Path file, ImportSettings settings, Path database, String tableName) {
        return load(file, settings, database, tableName, ImportProgressListener.IGNORE);
    }

    /**
     * Ouvre le fichier dans le format demandé, puis le charge.
     *
     * @param progressListener prévenu à chaque vidage du tampon de l'appender
     * @throws ImportRejectedException si le fichier est illisible ou ne contient aucune donnée
     */
    public ImportedTable load(
            Path file,
            ImportSettings settings,
            Path database,
            String tableName,
            ImportProgressListener progressListener) {
        if (settings instanceof ParquetSettings) {
            return parquetLoader.load(file, database, tableName, progressListener);
        }
        try (var source = RowSources.open(file, settings)) {
            return load(source, database, tableName, progressListener);
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    /**
     * Charge les lignes d'une source déjà ouverte. La base est recréée : un fichier qui porterait déjà
     * ce nom est le résidu d'un chargement abandonné, et sa table ferait échouer la création de la
     * nôtre.
     *
     * @throws ImportRejectedException si la source ne contient aucune ligne de données
     */
    public ImportedTable load(
            RowSource source, Path database, String tableName, ImportProgressListener progressListener) {
        if (!GENERATED_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Nom de table non conforme : " + tableName);
        }
        databases.delete(database);
        try (var connection = databases.open(database)) {
            var columns = source.columns();
            createTable(connection, tableName, columns);
            var loaded = append(connection, tableName, source, progressListener);
            if (loaded.rows() == 0) {
                throw new ImportRejectedException("import.error.aucune-donnee");
            }
            return new ImportedTable(database, tableName, columns, loaded.rows(), loaded.raggedRows());
        } catch (SQLException e) {
            databases.delete(database);
            throw new IllegalStateException("Chargement impossible dans la table " + tableName, e);
        } catch (RuntimeException e) {
            databases.delete(database); // une base à moitié chargée ne doit pas rester sur le disque
            throw e;
        }
    }

    private static LoadedRows append(
            Connection connection, String tableName, RowSource source, ImportProgressListener progressListener)
            throws SQLException {
        int columnCount = source.columns().size();
        long rows = 0;
        long raggedRows = 0;
        try (var appender = connection.unwrap(DuckDBConnection.class).createAppender(MAIN_SCHEMA, tableName)) {
            while (source.next()) {
                if (source.fieldCount() != columnCount) {
                    raggedRows++;
                }
                appender.beginRow();
                appender.append(source.rowNumber());
                for (int column = 0; column < columnCount; column++) {
                    String value = source.field(column);
                    if (value == null) {
                        appender.appendNull();
                    } else {
                        appender.append(value);
                    }
                }
                appender.endRow();
                rows++;
                if (rows % FLUSH_EVERY_ROWS == 0) {
                    appender.flush();
                    progressListener.progress(source.percent(), rows);
                }
            }
            appender.flush();
        }
        progressListener.progress(source.percent(), rows);
        return new LoadedRows(rows, raggedRows);
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

    private record LoadedRows(long rows, long raggedRows) {}
}
