package ci.cnam.dqanalyzer.execution;

import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Point d'entrée des analyses pour les écrans et les autres modules (EXE-01) : crée l'exécution, la
 * confie au {@link AnalysisRunner} en tâche de fond (EXE-02) et rend la main tout de suite avec son
 * numéro.
 */
@Service
public class AnalysisService {

    private final JdbcTemplate jdbc;
    private final AnalysisRunner runner;

    public AnalysisService(JdbcTemplate jdbc, AnalysisRunner runner) {
        this.jdbc = jdbc;
        this.runner = runner;
    }

    /**
     * Enregistre l'analyse et la lance.
     *
     * <p>Sans transaction, volontairement : l'insertion doit être validée en base avant que le thread
     * de l'analyse ne lise la ligne, sinon il ne la trouverait pas.
     *
     * @return le numéro de l'analyse, à suivre avec {@link #progress(long)}
     */
    public long start(long sourceFileId, long templateId, long userId) {
        Long runId = jdbc.queryForObject(
                """
                INSERT INTO analysis_run (source_file_id, template_id, launched_by, status, progress)
                VALUES (?, ?, ?, 'PENDING', 0)
                RETURNING id
                """,
                Long.class,
                sourceFileId,
                templateId,
                userId);
        runner.run(runId);
        return runId;
    }

    /**
     * Avancement d'une analyse, lu toutes les 2 secondes par la barre de progression.
     *
     * @param resultStatus OK, ALERT ou KO ; null tant que l'analyse n'est pas terminée
     * @param error message destiné à l'analyste quand l'analyse a échoué
     */
    public record Progress(String status, int progress, String resultStatus, Double globalScore, String error) {

        public boolean finished() {
            return "DONE".equals(status) || "FAILED".equals(status);
        }

        public boolean failed() {
            return "FAILED".equals(status);
        }
    }

    /** @throws NoSuchElementException si l'analyse n'existe pas */
    public Progress progress(long runId) {
        return jdbc
                .query(
                        """
                        SELECT status, progress, result_status, global_score, error_message
                        FROM analysis_run WHERE id = ?
                        """,
                        (resultSet, row) -> new Progress(
                                resultSet.getString(1),
                                resultSet.getInt(2),
                                resultSet.getString(3),
                                resultSet.getObject(4) == null ? null : resultSet.getDouble(4),
                                resultSet.getString(5)),
                        runId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Analyse inconnue : " + runId));
    }
}
