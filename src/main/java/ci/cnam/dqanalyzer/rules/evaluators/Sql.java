package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Collections;
import java.util.function.UnaryOperator;

/** Morceaux de SQL DuckDB réutilisés par les évaluateurs. Toujours appelés avec des identifiants déjà cités. */
final class Sql {

    private Sql() {}

    /** La cellule contient autre chose que des espaces. */
    static SqlFragment filled(String column) {
        return SqlFragment.of(column + " IS NOT NULL AND trim(CAST(" + column + " AS VARCHAR)) <> ''");
    }

    /** La cellule est vide, absente ou ne contient que des espaces. */
    static SqlFragment empty(String column) {
        return SqlFragment.of(column + " IS NULL OR trim(CAST(" + column + " AS VARCHAR)) = ''");
    }

    /** Valeur numérique de la cellule (virgule décimale acceptée) ou NULL si ce n'est pas un nombre. */
    static String number(String column) {
        return "TRY_CAST(replace(trim(CAST(" + column + " AS VARCHAR)), ',', '.') AS DOUBLE)";
    }

    /** Date de la cellule selon un format DuckDB (un « ? » à lier) ou NULL si elle ne respecte pas le format. */
    static String date(String column) {
        return "CAST(try_strptime(trim(CAST(" + column + " AS VARCHAR)), ?) AS DATE)";
    }

    /** Une liste de n « ? » séparés par des virgules, chacun passé par la même normalisation que la colonne. */
    static String placeholders(int count, UnaryOperator<String> wrap) {
        return String.join(", ", Collections.nCopies(count, wrap.apply("?")));
    }
}
