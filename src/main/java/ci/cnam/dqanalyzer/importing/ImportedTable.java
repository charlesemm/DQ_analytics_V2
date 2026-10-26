package ci.cnam.dqanalyzer.importing;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Résultat d'un import : la table DuckDB prête à être analysée. C'est le point de contact entre
 * {@code importing} et {@code execution}, qui en fera un {@code Dataset}.
 *
 * @param database fichier DuckDB sur disque qui contient la table
 * @param tableName nom de la table ; elle contient {@code _row_num} puis les colonnes du fichier
 * @param columns colonnes du fichier, dans leur ordre d'origine, après nettoyage des en-têtes
 * @param rowCount nombre de lignes de données chargées (l'en-tête n'en fait pas partie)
 * @param raggedRowCount lignes dont le nombre de champs ne correspondait pas à l'en-tête : les
 *     champs manquants valent « absent », les champs en trop sont ignorés
 */
public record ImportedTable(Path database, String tableName, List<String> columns, long rowCount, long raggedRowCount) {

    public ImportedTable {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(tableName, "tableName");
        columns = List.copyOf(columns);
    }
}
