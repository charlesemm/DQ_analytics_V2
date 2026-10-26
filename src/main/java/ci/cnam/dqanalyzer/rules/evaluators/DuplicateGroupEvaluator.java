package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluator;
import ci.cnam.dqanalyzer.rules.RuleResult;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Base des règles d'unicité. Contrairement aux règles ligne à ligne ({@link RowPredicateEvaluator}),
 * l'anomalie ne se décide pas sur une ligne isolée : il faut avoir lu tout le fichier pour savoir
 * qu'une valeur apparaît deux fois. Le travail se fait donc par agrégation (CDC §9.3, « règles
 * d'ensemble ») et la sous-classe ne fournit que les colonnes qui forment la clé.
 *
 * <p>Conventions appliquées ici :
 *
 * <ul>
 *   <li>toutes les lignes d'un groupe de doublons sont en anomalie (CDC §7) ; le nombre de lignes en
 *       surplus se déduit du rapport : anomalies − groupes ;
 *   <li>les groupes sont numérotés de 1 à N dans l'ordre d'apparition dans le fichier et les
 *       anomalies sont émises groupe par groupe (RAP-04) ;
 *   <li>une ligne dont la clé est incomplète (valeur absente ou blanche) n'est pas contrôlée :
 *       l'absence relève de la complétude (CMP-01) et deux clés vides ne sont pas un doublon.
 * </ul>
 */
public abstract class DuplicateGroupEvaluator implements RuleEvaluator {

    private static final int FETCH_SIZE = 5_000;

    /** Colonnes formant la clé d'unicité : une seule (UNI-01) ou plusieurs (UNI-02). */
    protected abstract List<String> keyColumns(RuleDefinition rule);

    /** Message par défaut, lisible par un non-informaticien (ERG-02), si la règle n'en définit pas. */
    protected abstract String defaultMessage(RuleDefinition rule);

    @Override
    public final RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
        var columns = keyColumns(rule);
        if (columns.isEmpty()) {
            throw new RuleEvaluationException("La règle " + rule.ruleCode() + " ne cible aucune colonne");
        }
        var quoted = columns.stream().map(data::quotedColumn).toList();
        String columnLabel = String.join(" + ", columns);
        String message = rule.message() == null || rule.message().isBlank() ? defaultMessage(rule) : rule.message();

        // Clé normalisée, une expression par colonne : on groupe sur le tuple de colonnes et jamais
        // sur leur concaténation, sinon « AB » + « C » et « A » + « BC » seraient le même doublon.
        String keyColumnList = indexes(quoted).mapToObj(i -> "k" + i).collect(Collectors.joining(", "));
        String keySelect = indexes(quoted)
                .mapToObj(i -> rule.normalization().apply(quoted.get(i)) + " AS k" + i)
                .collect(Collectors.joining(", "));
        String completeKey = quoted.stream()
                .map(column -> column + " IS NOT NULL AND trim(CAST(" + column + " AS VARCHAR)) <> ''")
                .collect(Collectors.joining(" AND "));

        try {
            long checked;
            long duplicated;
            long groups;
            String countSql = "WITH scoped AS (SELECT " + keySelect + " FROM " + data.quotedTable() + " WHERE "
                    + completeKey + "), grouped AS (SELECT count(*) AS n FROM scoped GROUP BY " + keyColumnList + ") "
                    + "SELECT coalesce(sum(n), 0), coalesce(sum(n) FILTER (WHERE n > 1), 0),"
                    + " count(*) FILTER (WHERE n > 1) FROM grouped";
            try (PreparedStatement ps = data.connection().prepareStatement(countSql);
                    var rs = ps.executeQuery()) {
                rs.next();
                checked = rs.getLong(1);
                duplicated = rs.getLong(2);
                groups = rs.getLong(3);
            }

            // Les valeurs d'origine, non normalisées, sont celles reportées dans l'anomalie (CDC §6).
            String valueSelect = indexes(quoted)
                    .mapToObj(i -> "CAST(" + quoted.get(i) + " AS VARCHAR) AS v" + i)
                    .collect(Collectors.joining(", "));
            String valueColumnList = indexes(quoted).mapToObj(i -> "v" + i).collect(Collectors.joining(", "));
            String partition = "PARTITION BY " + keyColumnList;
            String anomaliesSql = "WITH scoped AS (SELECT " + Dataset.ROW_NUM_COLUMN + ", " + keySelect + ", "
                    + valueSelect + " FROM " + data.quotedTable() + " WHERE " + completeKey + "), "
                    + "marked AS (SELECT *, count(*) OVER (" + partition + ") AS n, min(" + Dataset.ROW_NUM_COLUMN
                    + ") OVER (" + partition + ") AS first_row FROM scoped) SELECT " + Dataset.ROW_NUM_COLUMN
                    + ", dense_rank() OVER (ORDER BY first_row) AS grp, " + valueColumnList
                    + " FROM marked WHERE n > 1 ORDER BY grp, " + Dataset.ROW_NUM_COLUMN;
            try (PreparedStatement ps = data.connection().prepareStatement(anomaliesSql)) {
                ps.setFetchSize(FETCH_SIZE);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        var values = new StringBuilder();
                        for (int i = 0; i < quoted.size(); i++) {
                            values.append(i == 0 ? "" : " | ").append(rs.getString(3 + i));
                        }
                        sink.accept(new Anomaly(rs.getLong(1), columnLabel, values.toString(), message, rs.getLong(2)));
                    }
                }
            }
            return new RuleResult(checked, checked - duplicated, groups);
        } catch (SQLException e) {
            throw new RuleEvaluationException("Échec de la règle " + rule.ruleCode() + " (" + type() + ")", e);
        }
    }

    private static IntStream indexes(List<String> columns) {
        return IntStream.range(0, columns.size());
    }
}
