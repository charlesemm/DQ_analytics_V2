package ci.cnam.dqanalyzer.rules;

/**
 * Normalisation appliquée à la valeur avant contrôle (CDC §6). La valeur affichée dans les anomalies
 * reste toujours la valeur d'origine.
 */
public record Normalization(boolean trim, boolean upperCase, boolean stripAccents) {

    public static final Normalization NONE = new Normalization(false, false, false);

    /** Applique la normalisation à une expression SQL DuckDB déjà sûre (identifiant cité). */
    public String apply(String sqlExpression) {
        String expr = "CAST(" + sqlExpression + " AS VARCHAR)";
        if (trim) {
            expr = "trim(" + expr + ")";
        }
        if (stripAccents) {
            expr = "strip_accents(" + expr + ")";
        }
        if (upperCase) {
            expr = "upper(" + expr + ")";
        }
        return expr;
    }
}
