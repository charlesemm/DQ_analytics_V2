package ci.cnam.dqanalyzer.shared.duckdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Morceau de SQL et ses paramètres positionnels, dans l'ordre des "?".
 * Le texte ne doit contenir que des mots-clés, des identifiants passés par {@link DuckDbIdentifiers#quote}
 * et des "?" : jamais une valeur saisie par l'utilisateur.
 */
public record SqlFragment(String sql, List<Object> params) {

    public static final SqlFragment TRUE = new SqlFragment("TRUE", List.of());

    public SqlFragment {
        Objects.requireNonNull(sql, "sql");
        params = List.copyOf(params);
    }

    public static SqlFragment of(String sql, Object... params) {
        return new SqlFragment(sql, List.of(params));
    }

    public SqlFragment and(SqlFragment other) {
        var all = new ArrayList<>(params);
        all.addAll(other.params);
        return new SqlFragment("(" + sql + ") AND (" + other.sql + ")", all);
    }
}
