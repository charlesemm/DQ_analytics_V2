package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ImportPreview;
import ci.cnam.dqanalyzer.importing.ImportPreview.PreviewRow;
import ci.cnam.dqanalyzer.importing.ImportProgressListener;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.ImportedTable;
import ci.cnam.dqanalyzer.importing.domain.HeaderNames;
import ci.cnam.dqanalyzer.importing.domain.RowSource;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Charge un fichier Parquet dans sa table DuckDB. Seul format que l'outil ne lit pas lui-même :
 * DuckDB sait le faire nativement, en une requête, sans passer par Java. Le fichier reste sur le
 * disque et n'est jamais chargé en mémoire (PERF-03).
 *
 * <p>Toutes les colonnes sont ramenées en texte, comme pour les autres formats : les règles
 * contrôlent le texte brut, et une analyse ne doit pas dépendre du type déclaré dans le fichier.
 */
@Component
class DuckDbParquetLoader {

    private final DuckDbDatabases databases;

    DuckDbParquetLoader(DuckDbDatabases databases) {
        this.databases = Objects.requireNonNull(databases, "databases");
    }

    /**
     * @throws ImportRejectedException si le fichier est illisible ou ne contient aucune ligne
     */
    ImportedTable load(Path file, Path database, String tableName, ImportProgressListener progressListener) {
        databases.delete(database);
        try (var connection = databases.open(database)) {
            var columns = readColumns(connection, file);
            long rowCount = createTable(connection, tableName, file, columns);
            if (rowCount == 0) {
                throw new ImportRejectedException("import.error.aucune-donnee");
            }
            // Le chargement se fait en une requête : il n'y a pas d'avancement intermédiaire à annoncer.
            progressListener.progress(RowSource.MAX_PERCENT_BEFORE_END, rowCount);
            return new ImportedTable(database, tableName, columns.names(), rowCount, 0);
        } catch (SQLException e) {
            databases.delete(database);
            throw new ImportRejectedException("import.error.fichier-illisible");
        } catch (RuntimeException e) {
            databases.delete(database);
            throw e;
        }
    }

    /**
     * Premières lignes du fichier, sans rien charger (IMP-04). DuckDB ne lit que ce qu'il faut : un
     * fichier Parquet de 2 Go s'aperçoit aussi vite qu'un fichier de 2 Ko.
     *
     * @throws ImportRejectedException si le fichier est illisible
     */
    ImportPreview preview(Path file, int limit) {
        try (var connection = databases.openInMemory()) {
            var columns = readColumns(connection, file);
            var rows = new ArrayList<PreviewRow>();
            var sql = new StringBuilder("SELECT ");
            for (int index = 0; index < columns.original().size(); index++) {
                sql.append(index == 0 ? "" : ", ")
                        .append("CAST(")
                        .append(DuckDbIdentifiers.quote(columns.original().get(index)))
                        .append(" AS VARCHAR)");
            }
            sql.append(" FROM read_parquet(?) LIMIT ?");
            try (var statement = connection.prepareStatement(sql.toString())) {
                statement.setString(1, file.toAbsolutePath().toString());
                statement.setInt(2, limit + 1); // une ligne de plus : elle dit s'il en reste
                try (var result = statement.executeQuery()) {
                    boolean truncated = false;
                    while (result.next()) {
                        if (rows.size() == limit) {
                            truncated = true; // la ligne lue en trop prouve qu'il en reste
                            break;
                        }
                        var values = new ArrayList<String>(columns.names().size());
                        for (int column = 1; column <= columns.names().size(); column++) {
                            String value = result.getString(column);
                            values.add(value == null || value.isEmpty() ? null : value);
                        }
                        rows.add(new PreviewRow(rows.size() + 1L, values));
                    }
                    return new ImportPreview(columns.names(), rows, truncated);
                }
            }
        } catch (SQLException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    /** Noms des colonnes du fichier, dans l'ordre du schéma, rendus utilisables comme ailleurs. */
    private static Columns readColumns(Connection connection, Path file) throws SQLException {
        var original = new ArrayList<String>();
        try (var statement = connection.prepareStatement("DESCRIBE SELECT * FROM read_parquet(?)")) {
            statement.setString(1, file.toAbsolutePath().toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    original.add(rows.getString("column_name"));
                }
            }
        }
        if (original.isEmpty()) {
            throw new ImportRejectedException("import.error.entete-vide");
        }
        return new Columns(original, HeaderNames.normalize(original));
    }

    /**
     * Crée la table et la remplit en une requête. {@code row_number()} donne le n° de ligne d'origine :
     * un fichier Parquet n'a pas d'en-tête, sa première ligne de données est donc la ligne 1.
     */
    private static long createTable(Connection connection, String tableName, Path file, Columns columns)
            throws SQLException {
        var sql = new StringBuilder("CREATE TABLE ")
                .append(DuckDbIdentifiers.quote(tableName))
                .append(" AS SELECT CAST(row_number() OVER () AS BIGINT) AS ")
                .append(DuckDbIdentifiers.quote(Dataset.ROW_NUM_COLUMN));
        for (int index = 0; index < columns.names().size(); index++) {
            sql.append(", NULLIF(CAST(")
                    .append(DuckDbIdentifiers.quote(columns.original().get(index)))
                    .append(" AS VARCHAR), '') AS ")
                    .append(DuckDbIdentifiers.quote(columns.names().get(index)));
        }
        sql.append(" FROM read_parquet(?)");
        try (var statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, file.toAbsolutePath().toString());
            statement.execute();
        }
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM " + DuckDbIdentifiers.quote(tableName))) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Noms du schéma Parquet et noms retenus pour la table : les seconds peuvent différer. */
    private record Columns(List<String> original, List<String> names) {}
}
