package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluator;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.rules.RuleResult;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * UNI-04 — Doublons approchés : « N'GUESSAN » / « NGUESSAN ». Compare les lignes deux à deux avec une mesure
 * de ressemblance (Jaro-Winkler de 0 à 1, ou Levenshtein = nombre de lettres à changer).
 *
 * <p>Comparer chaque ligne à toutes les autres coûte n² comparaisons (10 milliards pour 100 000 lignes) :
 * le paramètre {@code blockColumn} (par exemple la date de naissance) limite la comparaison aux lignes qui ont
 * la même valeur dans cette colonne. C'est la technique dite de « blocage », indispensable sur gros fichier.
 *
 * <p>Simplification assumée en V1 : le groupe d'une ligne est la première ligne qui lui ressemble ; si A ressemble
 * à B et B à C sans que A ressemble à C, on peut obtenir deux groupes au lieu d'un.
 */
@Component
public class Uni04ApproximateDuplicateEvaluator implements RuleEvaluator {

    @Override
    public String type() {
        return "UNI-04";
    }

    @Override
    public Dimension dimension() {
        return Dimension.UNIQUENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(
                ParamSchema.ColumnArity.MANY,
                List.of(
                        new Param("algorithm", ParamType.TEXT, true, "param.similarityAlgorithm"),
                        new Param("threshold", ParamType.DECIMAL, true, "param.similarityThreshold"),
                        new Param("blockColumn", ParamType.COLUMN, false, "param.blockColumn")));
    }

    @Override
    public RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
        var params = RuleParams.of(rule);
        double threshold = params.number("threshold");
        String similar =
                switch (params.text("algorithm").toLowerCase(Locale.ROOT)) {
                    case "jaro-winkler", "jaro_winkler", "jarowinkler" -> "jaro_winkler_similarity(a.txt, b.txt) >= ?";
                    case "levenshtein" -> "levenshtein(a.txt, b.txt) <= ?";
                    default ->
                        throw new RuleEvaluationException(
                                "Règle " + rule.ruleCode() + " : algorithme inconnu (jaro-winkler ou levenshtein)");
                };

        var parts = new ArrayList<String>();
        var filled = new ArrayList<String>();
        for (String column : rule.targetColumns()) {
            String quoted = data.quotedColumn(column);
            parts.add(rule.normalization().apply(quoted));
            filled.add("(" + Sql.filled(quoted).sql() + ")");
        }
        if (parts.isEmpty()) {
            throw new RuleEvaluationException("La règle " + rule.ruleCode() + " ne cible aucune colonne");
        }
        String block = params.optionalText("blockColumn")
                .map(c -> "trim(CAST(" + data.quotedColumn(c) + " AS VARCHAR))")
                .orElse("''");

        String sql =
                """
                WITH k AS (
                    SELECT %s AS row_num, concat_ws(' ', %s) AS txt, %s AS blk
                    FROM %s WHERE %s
                ),
                pairs AS (
                    SELECT a.row_num AS ra, b.row_num AS rb
                    FROM k a JOIN k b ON a.blk = b.blk AND a.row_num < b.row_num
                    WHERE %s
                ),
                members AS (
                    SELECT ra AS row_num, ra AS first_row FROM pairs
                    UNION SELECT rb, ra FROM pairs
                ),
                grouped AS (
                    SELECT row_num, min(first_row) AS first_row FROM members GROUP BY row_num
                )
                SELECT g.row_num, k.txt, dense_rank() OVER (ORDER BY g.first_row) AS group_id,
                       (SELECT count(*) FROM k) AS checked
                FROM grouped g JOIN k USING (row_num)
                ORDER BY group_id, g.row_num
                """
                        .formatted(
                                Dataset.ROW_NUM_COLUMN,
                                String.join(", ", parts),
                                block,
                                data.quotedTable(),
                                String.join(" AND ", filled),
                                similar);
        String column = String.join(" + ", rule.targetColumns());
        String message = rule.message() == null || rule.message().isBlank()
                ? "Doublon probable : valeur très proche d'une autre ligne du même groupe."
                : rule.message();

        try (var ps = data.connection().prepareStatement(sql)) {
            ps.setFetchSize(5_000);
            ps.setObject(1, threshold);
            long anomalies = 0;
            long maxGroup = 0;
            Long checked = null;
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    anomalies++;
                    maxGroup = Math.max(maxGroup, rs.getLong(3));
                    checked = rs.getLong(4);
                    sink.accept(new Anomaly(rs.getLong(1), column, rs.getString(2), message, rs.getLong(3)));
                }
            }
            if (checked == null) {
                try (var st = data.connection().createStatement();
                        var rs = st.executeQuery("SELECT count(*) FROM " + data.quotedTable() + " WHERE "
                                + String.join(" AND ", filled))) {
                    rs.next();
                    checked = rs.getLong(1);
                }
            }
            return new RuleResult(checked, checked - anomalies, maxGroup);
        } catch (SQLException e) {
            throw new RuleEvaluationException("Échec de la règle " + rule.ruleCode() + " (" + type() + ")", e);
        }
    }
}
