package ci.cnam.dqanalyzer.rules;

import java.util.List;

/**
 * Description des paramètres attendus par un type de règle. Sert à valider une règle et à générer
 * son formulaire (REG-02).
 */
public record ParamSchema(ColumnArity columns, List<Param> params) {

    public ParamSchema {
        params = List.copyOf(params);
    }

    public static ParamSchema singleColumn(Param... params) {
        return new ParamSchema(ColumnArity.ONE, List.of(params));
    }

    /** Règles portant sur une liste de colonnes, par ex. une clé composée (UNI-02). */
    public static ParamSchema manyColumns(Param... params) {
        return new ParamSchema(ColumnArity.MANY, List.of(params));
    }

    public enum ColumnArity {
        ONE,
        TWO,
        MANY
    }

    public enum ParamType {
        TEXT,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        TEXT_LIST,
        COLUMN,
        REFERENCE_TABLE,
        EXPRESSION
    }

    /** @param labelKey clé du libellé français dans messages.properties */
    public record Param(String name, ParamType type, boolean required, String labelKey) {}
}
