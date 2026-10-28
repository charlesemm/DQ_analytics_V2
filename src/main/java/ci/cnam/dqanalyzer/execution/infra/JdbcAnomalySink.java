package ci.cnam.dqanalyzer.execution.infra;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Écrit les anomalies d'une règle dans la table {@code anomaly}, par paquets de {@value #BATCH}.
 *
 * <p>Au-delà de {@code maxStored} anomalies pour une même règle, les suivantes sont comptées mais
 * plus enregistrées : le score reste exact, puisqu'il vient des comptages de l'évaluateur, et une
 * règle mal paramétrée sur un fichier de 2 Go ne remplit pas la base (PERF-03).
 */
public class JdbcAnomalySink implements AnomalySink, AutoCloseable {

    private static final int BATCH = 1_000;

    private static final String INSERT = "INSERT INTO anomaly (run_id, rule_id, row_num, column_name, cell_value,"
            + " message, duplicate_group) VALUES (?, ?, ?, ?, ?, ?, ?)";

    /** Longueurs des colonnes de la table anomaly : une valeur trop longue est coupée, pas refusée. */
    private static final int COLUMN_MAX = 255;

    private static final int VALUE_MAX = 1_000;
    private static final int MESSAGE_MAX = 500;

    private final JdbcTemplate jdbc;
    private final long runId;
    private final long ruleId;
    private final long maxStored;
    private final List<Object[]> buffer = new ArrayList<>(BATCH);
    private long received;

    public JdbcAnomalySink(JdbcTemplate jdbc, long runId, long ruleId, long maxStored) {
        this.jdbc = jdbc;
        this.runId = runId;
        this.ruleId = ruleId;
        this.maxStored = maxStored;
    }

    @Override
    public void accept(Anomaly anomaly) {
        if (++received > maxStored) {
            return;
        }
        buffer.add(new Object[] {
            runId,
            ruleId,
            anomaly.rowNum(),
            cut(anomaly.column(), COLUMN_MAX),
            cut(anomaly.value(), VALUE_MAX),
            cut(anomaly.message(), MESSAGE_MAX),
            anomaly.duplicateGroup()
        });
        if (buffer.size() == BATCH) {
            flush();
        }
    }

    /** Nombre d'anomalies reçues, enregistrées ou non. */
    public long received() {
        return received;
    }

    /** Vrai si la liste enregistrée est incomplète : le rapport doit le dire. */
    public boolean truncated() {
        return received > maxStored;
    }

    @Override
    public void close() {
        flush();
    }

    private void flush() {
        if (!buffer.isEmpty()) {
            jdbc.batchUpdate(INSERT, buffer);
            buffer.clear();
        }
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
