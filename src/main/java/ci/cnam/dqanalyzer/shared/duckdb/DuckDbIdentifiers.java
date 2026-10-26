package ci.cnam.dqanalyzer.shared.duckdb;

import java.util.Objects;

/**
 * Seule façon autorisée d'insérer un nom de colonne ou de table dans une requête DuckDB.
 * Les valeurs, elles, passent toujours par des paramètres "?".
 */
public final class DuckDbIdentifiers {

    private DuckDbIdentifiers() {}

    /** Entoure l'identifiant de guillemets doubles et double les guillemets internes. */
    public static String quote(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("Identifiant vide");
        }
        if (identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Identifiant invalide");
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
