package ci.cnam.dqanalyzer.execution;

/**
 * Publié quand une analyse se termine, réussie ou non. Le journal d'audit l'écoutera (TRA-01) sans
 * que le moteur ait à le connaître : une analyse n'a pas à savoir qui s'intéresse à son résultat.
 *
 * @param resultStatus OK, ALERT ou KO ; null si l'analyse a échoué
 */
public record AnalysisCompleted(long runId, long sourceFileId, long templateId, String status, String resultStatus) {}
