package ci.cnam.dqanalyzer.rules;

/** Sévérité d'une règle et son poids par défaut dans le score de dimension (CDC §7). */
public enum Severity {
    BLOCKING(3),
    MAJOR(2),
    MINOR(1);

    private final int defaultWeight;

    Severity(int defaultWeight) {
        this.defaultWeight = defaultWeight;
    }

    public int defaultWeight() {
        return defaultWeight;
    }
}
