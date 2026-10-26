package ci.cnam.dqanalyzer.testsupport;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

/**
 * Charge un petit CSV de src/test/resources/datasets dans une table DuckDB, comme le fera l'import :
 * colonne _row_num = n° de ligne dans le fichier (en-tête = ligne 1), autres colonnes en VARCHAR.
 * Séparateur ";", la valeur \N signifie NULL (pour distinguer « absent » de « vide »).
 */
public final class TestDatasets {

    private TestDatasets() {}

    public static Dataset loadCsv(Connection connection, String resource) {
        var format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(';')
                .setHeader()
                .setSkipHeaderRecord(true)
                .setNullString("\\N")
                .build();
        var stream = Objects.requireNonNull(
                TestDatasets.class.getClassLoader().getResourceAsStream(resource),
                "Ressource introuvable : " + resource);
        try (var parser = CSVParser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8), format)) {
            var columns = parser.getHeaderNames();
            String table = "ds_test";

            var ddl = new StringBuilder("CREATE TABLE " + DuckDbIdentifiers.quote(table) + " ("
                    + DuckDbIdentifiers.quote(Dataset.ROW_NUM_COLUMN) + " BIGINT");
            var placeholders = new StringBuilder("?");
            for (String column : columns) {
                ddl.append(", ").append(DuckDbIdentifiers.quote(column)).append(" VARCHAR");
                placeholders.append(", ?");
            }
            try (var st = connection.createStatement()) {
                st.execute(ddl.append(')').toString());
            }

            String insert = "INSERT INTO " + DuckDbIdentifiers.quote(table) + " VALUES (" + placeholders + ")";
            try (var ps = connection.prepareStatement(insert)) {
                long rowNum = 2;
                for (var record : parser) {
                    ps.setLong(1, rowNum++);
                    for (int i = 0; i < columns.size(); i++) {
                        ps.setString(i + 2, record.get(i));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return new Dataset(connection, table, new ArrayList<>(columns));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("Chargement du jeu de test impossible : " + resource, e);
        }
    }
}
