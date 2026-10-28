package ci.cnam.dqanalyzer.rules;

import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.rules.RuleCatalogService.RuleForm;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Essai d'une règle sur un fichier déjà importé (REG-03) : l'analyste voit tout de suite combien de
 * lignes sont contrôlées, combien sont conformes et à quoi ressemblent les anomalies.
 *
 * <p>Rien n'est enregistré : ni la règle, ni le résultat, ni les anomalies. Seules les
 * {@value #MAX_ANOMALIES} premières anomalies sont gardées en mémoire — un fichier de 2 Go peut en
 * produire des millions (PERF-03).
 */
@Component
public class RuleTester {

    public static final int MAX_ANOMALIES = 50;

    private final ImportService imports;
    private final DuckDbDatabases databases;
    private final RuleEvaluatorRegistry registry;
    private final ReferenceSource references;

    public RuleTester(
            ImportService imports,
            DuckDbDatabases databases,
            RuleEvaluatorRegistry registry,
            ReferenceSource references) {
        this.imports = imports;
        this.databases = databases;
        this.registry = registry;
        this.references = references;
    }

    /**
     * Ce que l'essai montre à l'écran.
     *
     * @param anomalies au plus {@value #MAX_ANOMALIES} anomalies, pour un aperçu
     * @param truncated vrai s'il y a plus d'anomalies que celles montrées
     */
    public record RuleTestResult(
            long fileRows,
            long checkedRows,
            long conformingRows,
            double scorePercent,
            List<Anomaly> anomalies,
            boolean truncated) {

        public RuleTestResult {
            anomalies = List.copyOf(anomalies);
        }

        public long anomalyRows() {
            return checkedRows - conformingRows;
        }
    }

    /** @throws RuleEvaluationException si la règle ne peut pas s'exécuter sur ce fichier */
    public RuleTestResult test(RuleForm form, long sourceFileId) {
        var evaluator = registry.get(form.type());
        var table = imports.tableOf(sourceFileId);
        var rule = new RuleDefinition(
                null,
                "R-ESSAI",
                0,
                form.name(),
                form.type(),
                evaluator.dimension(),
                form.targetColumns(),
                form.params(),
                form.normalization(),
                form.severity(),
                form.thresholdPercent(),
                form.message());
        var collected = new ArrayList<Anomaly>();
        var sink = new CappedSink(collected);
        try (Connection connection = databases.open(table.database())) {
            // Une regle de reference (EXA-01, INT-01) a besoin de son referentiel a cote du fichier.
            var provider = references.loadInto(connection, referencedTables(rule));
            var result = evaluator.evaluate(
                    new Dataset(connection, table.tableName(), table.columns()), rule, provider, sink);
            return new RuleTestResult(
                    table.rowCount(),
                    result.checkedRows(),
                    result.conformingRows(),
                    score(result),
                    collected,
                    sink.exceeded);
        } catch (SQLException e) {
            throw new RuleEvaluationException("L'essai n'a pas pu être exécuté sur ce fichier : " + e.getMessage());
        }
    }

    /**
     * Versions de référence citées par la règle. Le paramètre {@code referenceTableId} est le seul
     * moyen pour une règle de désigner un référentiel : il n'y en a jamais plus d'un par règle.
     */
    private static Set<Long> referencedTables(RuleDefinition rule) {
        Object id = rule.params().get("referenceTableId");
        if (id == null || id.toString().isBlank()) {
            return Set.of();
        }
        try {
            return Set.of((long) Double.parseDouble(id.toString()));
        } catch (NumberFormatException e) {
            throw new RuleEvaluationException("La table de référence indiquée n'est pas valide : " + id);
        }
    }

    /** Même calcul que le moteur : conformes / contrôlées × 100, et 100 % s'il n'y a rien à contrôler. */
    private static double score(RuleResult result) {
        if (result.checkedRows() == 0) {
            return 100;
        }
        return result.conformingRows() * 100.0 / result.checkedRows();
    }

    /** Garde les premières anomalies et se souvient qu'il y en avait d'autres. */
    private static final class CappedSink implements AnomalySink {

        private final List<Anomaly> collected;
        private boolean exceeded;

        private CappedSink(List<Anomaly> collected) {
            this.collected = collected;
        }

        @Override
        public void accept(Anomaly anomaly) {
            if (collected.size() < MAX_ANOMALIES) {
                collected.add(anomaly);
            } else {
                exceeded = true;
            }
        }
    }
}
