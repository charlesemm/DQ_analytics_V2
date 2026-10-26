package ci.cnam.dqanalyzer.rules;

/**
 * Reçoit les anomalies une par une, au fil de la lecture : on ne les garde jamais toutes en mémoire.
 * L'implémentation de production les écrit par lots dans la table anomaly.
 */
@FunctionalInterface
public interface AnomalySink {

    void accept(Anomaly anomaly);

    AnomalySink DISCARD = anomaly -> {};
}
