package ci.cnam.dqanalyzer.rules.expression;

import java.util.List;

/**
 * Condition saisie par l'analyste (CMP-03, COH-02), sous forme d'arbre. Pas de texte libre : on ne peut
 * exprimer que ce qui est prévu ici, ce qui exclut toute injection SQL.
 *
 * <p>Forme JSON, par exemple « statut = Ayant droit ET âge ≥ 18 » :
 * <pre>{"all": [ {"column": "statut", "op": "eq", "value": "Ayant droit"},
 *            {"column": "age", "op": "gte", "value": 18, "as": "number"} ]}</pre>
 */
public sealed interface Condition {

    /** Toutes les conditions doivent être vraies (ET). */
    record All(List<Condition> conditions) implements Condition {
        public All {
            conditions = List.copyOf(conditions);
        }
    }

    /** Au moins une condition doit être vraie (OU). */
    record Any(List<Condition> conditions) implements Condition {
        public Any {
            conditions = List.copyOf(conditions);
        }
    }

    /** Inverse une condition (NON). */
    record Not(Condition condition) implements Condition {}

    /**
     * Compare une colonne à une valeur, ou teste si elle est vide ou renseignée.
     *
     * @param values une valeur pour eq, ne, lt…, plusieurs pour in / not_in, aucune pour empty / filled
     * @param valueType TEXT compare le texte, NUMBER et DATE convertissent d'abord la cellule
     * @param dateFormat format des dates de la colonne (jj/mm/aaaa par défaut), pour valueType = DATE
     */
    record Comparison(String column, Operator op, List<String> values, ValueType valueType, String dateFormat)
            implements Condition {
        public Comparison {
            values = List.copyOf(values);
        }
    }

    enum Operator {
        EQ("="),
        NE("<>"),
        LT("<"),
        LTE("<="),
        GT(">"),
        GTE(">="),
        IN("IN"),
        NOT_IN("NOT IN"),
        EMPTY(""),
        FILLED("");

        private final String sql;

        Operator(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    enum ValueType {
        TEXT,
        NUMBER,
        DATE
    }
}
