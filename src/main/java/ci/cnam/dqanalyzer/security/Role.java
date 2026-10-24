package ci.cnam.dqanalyzer.security;

/** Profils utilisateurs (CDC §4). Utilisés dans @PreAuthorize("hasRole('ANALYST')"), etc. */
public enum Role {
    ADMIN,
    ANALYST,
    READER
}
