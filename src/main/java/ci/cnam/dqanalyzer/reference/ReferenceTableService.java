package ci.cnam.dqanalyzer.reference;

import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ImportedTable;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Chargement et consultation des tables de référence (REF-01, REF-02). Le fichier passe par le même
 * circuit qu'un import — formats, encodage et refus identiques — puis ses lignes sont recopiées dans
 * PostgreSQL, où elles sont durables et sauvegardées.
 *
 * <p>Charger deux fois le même nom crée les versions 1 puis 2 : <strong>une version déjà
 * enregistrée n'est jamais remplacée</strong> (REF-02), parce que des analyses passées la citent.
 */
@Service
public class ReferenceTableService {

    /** Lignes insérées par lot dans PostgreSQL : un référentiel de communes en compte des milliers. */
    private static final int BATCH = 1_000;

    /** Lignes montrées dans l'aperçu d'une version. */
    public static final int PREVIEW_ROWS = 50;

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final ImportService imports;
    private final DuckDbDatabases databases;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public ReferenceTableService(
            ImportService imports,
            DuckDbDatabases databases,
            JdbcTemplate jdbc,
            ObjectMapper json,
            TransactionTemplate transaction) {
        this.imports = imports;
        this.databases = databases;
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = transaction;
    }

    /**
     * Charge un fichier comme nouvelle version de la table nommée. La lecture du fichier se fait hors
     * transaction, l'écriture en base dans une seule : une erreur de lecture ne laisse pas de version
     * incomplète.
     *
     * @throws ci.cnam.dqanalyzer.importing.ImportRejectedException si le fichier ne peut pas être lu
     */
    public ReferenceTable load(String name, InputStream content, String originalName, long userId) {
        if (name == null || name.isBlank()) {
            throw new ReferenceRejectedException("reference.error.nom-obligatoire");
        }
        return imports.readAsTable(content, originalName, table -> save(name.trim(), table, userId));
    }

    /** Dernière version de chaque table, pour la liste et pour le formulaire de règle. */
    public List<ReferenceTable> currentVersions() {
        return jdbc.query(
                """
                SELECT r.id, r.name, r.version, r.columns::text, r.row_count, u.full_name, r.loaded_at
                FROM reference_table r
                JOIN app_user u ON u.id = r.loaded_by
                WHERE r.version = (SELECT max(v.version) FROM reference_table v WHERE v.name = r.name)
                ORDER BY r.name
                """,
                this::toReferenceTable);
    }

    /** Toutes les versions d'une table, de la plus récente à la plus ancienne (REF-02). */
    public List<ReferenceTable> versions(String name) {
        return jdbc.query(
                """
                SELECT r.id, r.name, r.version, r.columns::text, r.row_count, u.full_name, r.loaded_at
                FROM reference_table r
                JOIN app_user u ON u.id = r.loaded_by
                WHERE r.name = ?
                ORDER BY r.version DESC
                """,
                this::toReferenceTable,
                name);
    }

    /** @throws NoSuchElementException si cette version n'existe pas */
    public ReferenceTable byId(long referenceTableId) {
        return jdbc
                .query(
                        """
                        SELECT r.id, r.name, r.version, r.columns::text, r.row_count, u.full_name, r.loaded_at
                        FROM reference_table r
                        JOIN app_user u ON u.id = r.loaded_by
                        WHERE r.id = ?
                        """,
                        this::toReferenceTable,
                        referenceTableId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Table de référence inconnue : " + referenceTableId));
    }

    /** Premières lignes d'une version, dans l'ordre des colonnes déclarées (REF-01). */
    public List<List<String>> preview(long referenceTableId) {
        var columns = byId(referenceTableId).columns();
        return jdbc.query(
                "SELECT row_values::text FROM reference_value WHERE reference_table_id = ? ORDER BY id LIMIT ?",
                (resultSet, row) -> {
                    Map<String, Object> values = json.readValue(resultSet.getString(1), OBJECT);
                    return columns.stream()
                            .map(column -> values.get(column) == null
                                    ? ""
                                    : values.get(column).toString())
                            .toList();
                },
                referenceTableId,
                PREVIEW_ROWS);
    }

    /** Insertion de la version et de ses lignes dans une seule transaction : tout ou rien. */
    private ReferenceTable save(String name, ImportedTable table, long userId) {
        return transaction.execute(status -> insert(name, table, userId));
    }

    private ReferenceTable insert(String name, ImportedTable table, long userId) {
        int version = nextVersion(name);
        Long id = jdbc.queryForObject(
                """
                INSERT INTO reference_table (name, version, columns, row_count, loaded_by)
                VALUES (?, ?, ?::jsonb, ?, ?)
                RETURNING id
                """,
                Long.class,
                name,
                version,
                json.writeValueAsString(table.columns()),
                table.rowCount(),
                userId);
        copyRows(id, table);
        return byId(id);
    }

    private int nextVersion(String name) {
        Integer max = jdbc.queryForObject(
                "SELECT coalesce(max(version), 0) FROM reference_table WHERE name = ?", Integer.class, name);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * Recopie les lignes de la table DuckDB vers {@code reference_value}, par lots. Les lignes sont
     * lues au fil de l'eau : un référentiel volumineux ne passe jamais entièrement en mémoire
     * (PERF-03).
     */
    private void copyRows(long referenceTableId, ImportedTable table) {
        var columns = table.columns();
        String select = "SELECT "
                + String.join(", ", columns.stream().map(this::quoted).toList()) + " FROM " + quoted(table.tableName());
        var batch = new ArrayList<Object[]>(BATCH);
        try (var connection = databases.open(table.database());
                var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(select)) {
            while (rows.next()) {
                batch.add(new Object[] {referenceTableId, json.writeValueAsString(rowValues(columns, rows))});
                if (batch.size() == BATCH) {
                    flush(batch);
                }
            }
            flush(batch);
        } catch (SQLException e) {
            throw new ReferenceRejectedException("reference.error.copie-impossible");
        }
    }

    private Map<String, Object> rowValues(List<String> columns, ResultSet rows) throws SQLException {
        var values = new LinkedHashMap<String, Object>();
        for (int i = 0; i < columns.size(); i++) {
            values.put(columns.get(i), rows.getString(i + 1));
        }
        return values;
    }

    private void flush(List<Object[]> batch) {
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO reference_value (reference_table_id, row_values) VALUES (?, ?::jsonb)", batch);
        batch.clear();
    }

    private String quoted(String identifier) {
        return DuckDbIdentifiers.quote(identifier);
    }

    private ReferenceTable toReferenceTable(ResultSet resultSet, int row) throws SQLException {
        return new ReferenceTable(
                resultSet.getLong(1),
                resultSet.getString(2),
                resultSet.getInt(3),
                json.readValue(resultSet.getString(4), STRINGS),
                resultSet.getLong(5),
                resultSet.getString(6),
                resultSet.getTimestamp(7).toInstant());
    }
}
