package ci.cnam.dqanalyzer.execution;

import ci.cnam.dqanalyzer.execution.domain.Scoring;
import ci.cnam.dqanalyzer.execution.domain.Scoring.ScoredRule;
import ci.cnam.dqanalyzer.execution.infra.JdbcAnomalySink;
import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.ReferenceSource;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleDefinitionReader;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluatorRegistry;
import ci.cnam.dqanalyzer.rules.RuleResult;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import ci.cnam.dqanalyzer.templates.AnalysisTemplate;
import ci.cnam.dqanalyzer.templates.TemplateRules;
import ci.cnam.dqanalyzer.templates.TemplateService;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Exécute une analyse en tâche de fond : lit les règles du modèle, adapte leurs colonnes au fichier,
 * copie les tables de référence utilisées, applique chaque règle à la base DuckDB du fichier,
 * enregistre résultats et anomalies, puis calcule les scores (CDC §7).
 *
 * <p>Appelé uniquement par {@link AnalysisService} : {@code @Async} n'a d'effet que si l'appel vient
 * d'un autre bean, d'où la séparation entre le lancement et l'exécution.
 */
@Component
public class AnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private final JdbcTemplate jdbc;
    private final ImportService imports;
    private final DuckDbDatabases databases;
    private final TemplateService templates;
    private final RuleDefinitionReader rules;
    private final RuleEvaluatorRegistry registry;
    private final ReferenceSource references;
    private final ApplicationEventPublisher events;
    private final Scoring scoring = new Scoring();
    private final long maxAnomaliesPerRule;

    public AnalysisRunner(
            JdbcTemplate jdbc,
            ImportService imports,
            DuckDbDatabases databases,
            TemplateService templates,
            RuleDefinitionReader rules,
            RuleEvaluatorRegistry registry,
            ReferenceSource references,
            ApplicationEventPublisher events,
            @Value("${dq.analysis.max-anomalies-per-rule:1000000}") long maxAnomaliesPerRule) {
        this.jdbc = jdbc;
        this.imports = imports;
        this.databases = databases;
        this.templates = templates;
        this.rules = rules;
        this.registry = registry;
        this.references = references;
        this.events = events;
        this.maxAnomaliesPerRule = maxAnomaliesPerRule;
    }

    @Async
    public void run(long runId) {
        var run = jdbc.queryForMap("SELECT source_file_id, template_id FROM analysis_run WHERE id = ?", runId);
        long sourceFileId = ((Number) run.get("source_file_id")).longValue();
        long templateId = ((Number) run.get("template_id")).longValue();
        jdbc.update("UPDATE analysis_run SET status = 'RUNNING', progress = 0 WHERE id = ?", runId);
        String resultStatus = null;
        String status = "FAILED";
        try {
            resultStatus = execute(runId, sourceFileId, templateId);
            status = "DONE";
        } catch (RuntimeException e) {
            fail(runId, e);
        }
        events.publishEvent(new AnalysisCompleted(runId, sourceFileId, templateId, status, resultStatus));
    }

    private String execute(long runId, long sourceFileId, long templateId) {
        AnalysisTemplate template = templates.byId(templateId);
        var definitions = TemplateRules.remap(rules.forTemplate(templateId), template.columnMapping());
        if (definitions.isEmpty()) {
            throw new RuleEvaluationException("Ce modèle ne contient aucune règle active.");
        }
        var table = imports.tableOf(sourceFileId);
        var referenceIds = referencedTables(definitions);
        try (Connection duck = databases.open(table.database())) {
            var provider = references.loadInto(duck, referenceIds);
            // Les versions exactement utilisées sont figées : une analyse rouverte dans un an saura
            // sur quelle version du référentiel elle a été faite (TRA-02).
            for (long referenceId : referenceIds) {
                jdbc.update("INSERT INTO run_reference (run_id, reference_table_id) VALUES (?, ?)", runId, referenceId);
            }
            var dataset = new Dataset(duck, table.tableName(), table.columns());
            var scored = new ArrayList<ScoredRule>();
            var statuses = new ArrayList<RuleStatus>();
            for (int i = 0; i < definitions.size(); i++) {
                RuleDefinition rule = definitions.get(i);
                statuses.add(runOne(runId, dataset, rule, provider, scored));
                jdbc.update(
                        "UPDATE analysis_run SET progress = ? WHERE id = ?", (i + 1) * 100 / definitions.size(), runId);
            }
            var dimensionScores = scoring.dimensionScores(scored);
            OptionalDouble global = scoring.globalScore(dimensionScores, weights(template));
            RuleStatus globalStatus = scoring.globalStatus(statuses);
            jdbc.update(
                    """
                    UPDATE analysis_run SET status = 'DONE', progress = 100, result_status = ?, global_score = ?,
                           finished_at = now()
                    WHERE id = ?
                    """,
                    globalStatus.name(),
                    global.isPresent() ? round(global.getAsDouble()) : null,
                    runId);
            log.info(
                    "Analyse {} terminée : {} règles, statut {}, score global {}",
                    runId,
                    definitions.size(),
                    globalStatus,
                    global.isPresent() ? round(global.getAsDouble()) : "non mesuré");
            return globalStatus.name();
        } catch (SQLException e) {
            throw new IllegalStateException("La base du fichier " + sourceFileId + " n'a pas pu être ouverte", e);
        }
    }

    /** Une règle : évaluation, anomalies, score et statut. L'identifiant enregistré est celui de sa version. */
    private RuleStatus runOne(
            long runId, Dataset dataset, RuleDefinition rule, ReferenceProvider provider, List<ScoredRule> scored) {
        var evaluator = registry.get(rule.type());
        long ruleId = rule.id();
        RuleResult result;
        boolean truncated;
        try (var sink = new JdbcAnomalySink(jdbc, runId, ruleId, maxAnomaliesPerRule)) {
            result = evaluator.evaluate(dataset, rule, provider, sink);
            truncated = sink.truncated();
        }
        OptionalDouble score = scoring.ruleScore(result);
        RuleStatus status = scoring.ruleStatus(score, rule.thresholdPercent(), rule.severity());
        jdbc.update(
                """
                INSERT INTO rule_result (run_id, rule_id, checked_count, conforming_count, score, status,
                                         duplicate_groups, anomalies_truncated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                ruleId,
                result.checkedRows(),
                result.conformingRows(),
                score.isPresent() ? round(score.getAsDouble()) : null,
                status.name(),
                result.duplicateGroups(),
                truncated);
        scored.add(new ScoredRule(rule.dimension(), rule.severity(), rule.targetColumns(), score));
        return status;
    }

    /**
     * Une analyse échouée ne laisse pas de rapport à moitié faux : les résultats partiels sont
     * effacés. Le message destiné à l'analyste reste lisible ; le détail technique va au journal.
     */
    private void fail(long runId, RuntimeException e) {
        log.warn("Analyse {} en échec", runId, e);
        String message = e instanceof RuleEvaluationException
                ? e.getMessage()
                : "Erreur technique pendant l'analyse. Signalez-la au service informatique.";
        jdbc.update("DELETE FROM anomaly WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM rule_result WHERE run_id = ?", runId);
        jdbc.update("DELETE FROM run_reference WHERE run_id = ?", runId);
        jdbc.update(
                "UPDATE analysis_run SET status = 'FAILED', error_message = ?, finished_at = now() WHERE id = ?",
                message,
                runId);
    }

    /** Versions de référence citées par les règles du modèle, sans doublon. */
    private static Set<Long> referencedTables(List<RuleDefinition> definitions) {
        var ids = new LinkedHashSet<Long>();
        for (RuleDefinition rule : definitions) {
            Object id = rule.params().get("referenceTableId");
            if (id != null && !id.toString().isBlank()) {
                try {
                    ids.add((long) Double.parseDouble(id.toString()));
                } catch (NumberFormatException e) {
                    throw new RuleEvaluationException(
                            "La règle « " + rule.name() + " » cite une table de référence invalide : " + id);
                }
            }
        }
        return ids;
    }

    private static Map<Dimension, Double> weights(AnalysisTemplate template) {
        var weights = new EnumMap<Dimension, Double>(Dimension.class);
        template.dimensionWeights().forEach((dimension, weight) -> weights.put(dimension, (double) weight));
        return weights;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
