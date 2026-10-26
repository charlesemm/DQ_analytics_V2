package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluator;
import ci.cnam.dqanalyzer.rules.RuleResult;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Base des règles ligne à ligne (complétude, validité, cohérence…). Une sous-classe ne fournit que
 * la condition SQL qui identifie une ligne en anomalie ; le comptage et l'extraction sont faits ici.
 */
public abstract class RowPredicateEvaluator implements RuleEvaluator {

    private static final int FETCH_SIZE = 5_000;

    /** Condition vraie quand la ligne est en anomalie. */
    protected abstract SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs);

    /** Lignes concernées par la règle. Par défaut toutes ; à surcharger pour les règles conditionnelles. */
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return SqlFragment.TRUE;
    }

    /** Colonne dont la valeur est reportée dans l'anomalie. */
    protected String anomalyColumn(RuleDefinition rule) {
        return rule.firstColumn();
    }

    /** Message par défaut, lisible par un non-informaticien (ERG-02), si la règle n'en définit pas. */
    protected abstract String defaultMessage(RuleDefinition rule);

    @Override
    public final RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
        var violation = violation(data, rule, refs);
        var scope = scope(data, rule);
        String table = data.quotedTable();
        String column = anomalyColumn(rule);
        String quotedColumn = data.quotedColumn(column);
        String message = rule.message() == null || rule.message().isBlank() ? defaultMessage(rule) : rule.message();

        try {
            long checked;
            long failed;
            String countSql = "SELECT count(*), count(*) FILTER (WHERE " + violation.sql() + ") FROM " + table
                    + " WHERE " + scope.sql();
            try (PreparedStatement ps = data.connection().prepareStatement(countSql)) {
                bind(ps, violation.params(), scope.params());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    checked = rs.getLong(1);
                    failed = rs.getLong(2);
                }
            }

            var filter = scope.and(violation);
            String anomaliesSql = "SELECT " + Dataset.ROW_NUM_COLUMN + ", CAST(" + quotedColumn + " AS VARCHAR) FROM "
                    + table + " WHERE " + filter.sql() + " ORDER BY " + Dataset.ROW_NUM_COLUMN;
            try (PreparedStatement ps = data.connection().prepareStatement(anomaliesSql)) {
                ps.setFetchSize(FETCH_SIZE);
                bind(ps, filter.params());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sink.accept(new Anomaly(rs.getLong(1), column, rs.getString(2), message, null));
                    }
                }
            }
            return RuleResult.of(checked, checked - failed);
        } catch (SQLException e) {
            throw new RuleEvaluationException("Échec de la règle " + rule.ruleCode() + " (" + type() + ")", e);
        }
    }

    @SafeVarargs
    private static void bind(PreparedStatement ps, List<Object>... paramLists) throws SQLException {
        int index = 1;
        for (var params : paramLists) {
            for (Object param : params) {
                ps.setObject(index++, param);
            }
        }
    }
}
