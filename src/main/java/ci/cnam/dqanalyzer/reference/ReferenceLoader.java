package ci.cnam.dqanalyzer.reference;

import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.ReferenceSource;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Copie les tables de référence utilisées par une analyse depuis PostgreSQL (où elles sont
 * versionnées et sauvegardées) vers la base DuckDB de l'analyse, dans des tables « ref_&lt;id&gt; ».
 * Les règles EXA-01 et INT-01 les interrogent ensuite par une simple jointure, sans aller-retour
 * avec PostgreSQL : on ne fait jamais une requête par ligne du fichier.
 */
@Component
public class ReferenceLoader implements ReferenceSource {

    private static final int BATCH = 1_000;
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReferenceLoader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public ReferenceProvider loadInto(Connection duck, Collection<Long> referenceTableIds) throws SQLException {
        var tables = new HashMap<Long, String>();
        for (long id : referenceTableIds) {
            tables.put(id, copy(duck, id));
        }
        return id -> {
            String table = tables.get(id);
            if (table == null) {
                throw new RuleEvaluationException(
                        "La table de référence n° " + id + " n'a pas été chargée pour cette analyse");
            }
            return table;
        };
    }

    private String copy(Connection duck, long id) throws SQLException {
        List<String> columns = columnsOf(id);
        String table = "ref_" + id;
        var ddl = new StringBuilder("CREATE OR REPLACE TABLE " + DuckDbIdentifiers.quote(table) + " (");
        for (int i = 0; i < columns.size(); i++) {
            ddl.append(i == 0 ? "" : ", ")
                    .append(DuckDbIdentifiers.quote(columns.get(i)))
                    .append(" VARCHAR");
        }
        try (var statement = duck.createStatement()) {
            statement.execute(ddl.append(')').toString());
        }
        String insert = "INSERT INTO " + DuckDbIdentifiers.quote(table) + " VALUES ("
                + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
        try (PreparedStatement rows = duck.prepareStatement(insert)) {
            int[] pending = {0};
            // Lecture ligne à ligne et insertion par paquets : une référence de 500 000 communes ne
            // tient pas en mémoire, et readOnly laisse le pilote PostgreSQL lire par blocs.
            jdbc.query(
                    "SELECT row_values::text FROM reference_value WHERE reference_table_id = ? ORDER BY id",
                    resultSet -> {
                        try {
                            Map<String, Object> row = json.readValue(resultSet.getString(1), OBJECT);
                            for (int i = 0; i < columns.size(); i++) {
                                Object value = row.get(columns.get(i));
                                rows.setString(i + 1, value == null ? null : value.toString());
                            }
                            rows.addBatch();
                            if (++pending[0] % BATCH == 0) {
                                rows.executeBatch();
                            }
                        } catch (SQLException e) {
                            throw new IllegalStateException("Copie de la table de référence " + id + " impossible", e);
                        }
                    },
                    id);
            rows.executeBatch();
        }
        return table;
    }

    private List<String> columnsOf(long id) {
        String columns = jdbc
                .query(
                        "SELECT columns::text FROM reference_table WHERE id = ?",
                        (resultSet, row) -> resultSet.getString(1),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new RuleEvaluationException("La table de référence n° " + id + " n'existe pas ou plus"));
        return json.readValue(columns, STRINGS);
    }
}
