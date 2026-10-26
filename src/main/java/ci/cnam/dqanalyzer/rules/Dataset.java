package ci.cnam.dqanalyzer.rules;

import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * Fichier importé, chargé dans une table DuckDB. La table contient la colonne technique
 * {@value #ROW_NUM_COLUMN} (n° de ligne d'origine) puis les colonnes du fichier, toutes en VARCHAR.
 */
public record Dataset(Connection connection, String tableName, List<String> columns) {

    public static final String ROW_NUM_COLUMN = "_row_num";

    public Dataset {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tableName, "tableName");
        columns = List.copyOf(columns);
    }

    /** Nom de table cité, prêt à être inséré dans du SQL. */
    public String quotedTable() {
        return DuckDbIdentifiers.quote(tableName);
    }

    /** Vérifie que la colonne existe dans le fichier, puis la renvoie citée. */
    public String quotedColumn(String column) {
        if (!columns.contains(column)) {
            throw new RuleEvaluationException("La colonne « " + column + " » n'existe pas dans le fichier");
        }
        return DuckDbIdentifiers.quote(column);
    }
}
