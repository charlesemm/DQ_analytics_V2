package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluator;
import ci.cnam.dqanalyzer.rules.RuleResult;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Base des règles d'unicité exacte (UNI-01, UNI-02, UNI-03). Une règle d'ensemble : on ne peut pas juger
 * une ligne seule, il faut la comparer à toutes les autres. En SQL, c'est un regroupement (GROUP BY) sur la clé.
 *
 * <p>CDC §7 : une ligne est en anomalie si elle appartient à un groupe de doublons ; chaque groupe reçoit un
 * numéro (dans l'ordre de sa première ligne) pour que le rapport les regroupe (RAP-04).
 * Les lignes dont une colonne de la clé est vide ne sont pas contrôlées.
 */
abstract class DuplicateGroupsEvaluator implements RuleEvaluator {

    /** Faut-il appliquer la normalisation de la règle à la clé (UNI-03) ou comparer les valeurs exactes ? */
    protected abstract boolean normalizeKey();

    @Override
    public Dimension dimension() {
        return Dimension.UNIQUENESS;
    }

    @Override
    public final RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
        if (rule.targetColumns().isEmpty()) {
            throw new RuleEvaluationException("La règle " + rule.ruleCode() + " ne cible aucune colonne");
        }
        var keyParts = new ArrayList<String>();
        var filled = new ArrayList<String>();
        var display = new ArrayList<String>();
        for (String column : rule.targetColumns()) {
            String quoted = data.quotedColumn(column);
            String value =
                    normalizeKey() ? rule.normalization().apply(quoted) : "trim(CAST(" + quoted + " AS VARCHAR))";
            keyParts.add(value);
            filled.add(Sql.filled(quoted).sql());
            display.add("CAST(" + quoted + " AS VARCHAR)");
        }
        String sql =
                """
                WITH keyed AS (
                    SELECT %s AS row_num, list_value(%s) AS dup_key, concat_ws(' | ', %s) AS shown
                    FROM %s WHERE %s
                ),
                groups AS (
                    SELECT dup_key, dense_rank() OVER (ORDER BY min(row_num)) AS group_id
                    FROM keyed GROUP BY dup_key HAVING count(*) > 1
                )
                SELECT k.row_num, k.shown, g.group_id,
                       (SELECT count(*) FROM keyed) AS checked,
                       (SELECT count(*) FROM groups) AS group_count
                FROM keyed k JOIN groups g USING (dup_key)
                ORDER BY g.group_id, k.row_num
                """
                        .formatted(
                                Dataset.ROW_NUM_COLUMN,
                                String.join(", ", keyParts),
                                String.join(", ", display),
                                data.quotedTable(),
                                String.join(
                                        " AND ",
                                        filled.stream().map(f -> "(" + f + ")").toList()));
        String message = rule.message() == null || rule.message().isBlank()
                ? "Doublon : " + String.join(" + ", rule.targetColumns()) + " apparaît plusieurs fois."
                : rule.message();
        String column = String.join(" + ", rule.targetColumns());

        try (var st = data.connection().createStatement()) {
            st.setFetchSize(5_000);
            long duplicates = 0;
            long groups = 0;
            Long checked = null;
            try (var rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    checked = rs.getLong(4);
                    groups = rs.getLong(5);
                    duplicates++;
                    sink.accept(new Anomaly(rs.getLong(1), column, rs.getString(2), message, rs.getLong(3)));
                }
            }
            if (checked == null) {
                checked = countChecked(data, filled);
            }
            return new RuleResult(checked, checked - duplicates, groups);
        } catch (SQLException e) {
            throw new RuleEvaluationException("Échec de la règle " + rule.ruleCode() + " (" + type() + ")", e);
        }
    }

    private static long countChecked(Dataset data, List<String> filled) throws SQLException {
        String sql = "SELECT count(*) FROM " + data.quotedTable() + " WHERE "
                + String.join(" AND ", filled.stream().map(f -> "(" + f + ")").toList());
        try (var st = data.connection().createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
