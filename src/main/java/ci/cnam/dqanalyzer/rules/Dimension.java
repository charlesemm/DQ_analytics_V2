package ci.cnam.dqanalyzer.rules;

/** Dimensions de qualité V1 (CDC §2). Libellés français dans messages.properties : dimension.<NOM>. */
public enum Dimension {
    COMPLETENESS,
    UNIQUENESS,
    VALIDITY,
    ACCURACY,
    CONSISTENCY,
    TIMELINESS,
    REFERENTIAL_INTEGRITY
}
