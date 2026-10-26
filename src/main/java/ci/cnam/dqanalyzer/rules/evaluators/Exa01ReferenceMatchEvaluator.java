package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EXA-01 — Concordance avec une table de référence : « la date de naissance est celle de la base des assurés ».
 * Pour chaque ligne dont la clé existe dans la référence, la valeur comparée doit être identique.
 * Une clé absente de la référence n'est pas en anomalie ici : c'est le rôle d'INT-01.
 *
 * <p>Colonne 1 = la clé, colonne 2 = la valeur comparée. Paramètres : referenceTableId, referenceKey, referenceValue.
 */
@Component
public class Exa01ReferenceMatchEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "EXA-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.ACCURACY;
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(
                ParamSchema.ColumnArity.TWO,
                List.of(
                        new Param("referenceTableId", ParamType.REFERENCE_TABLE, true, "param.referenceTable"),
                        new Param("referenceKey", ParamType.TEXT, true, "param.referenceKey"),
                        new Param("referenceValue", ParamType.TEXT, true, "param.referenceValue")));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return Sql.filled(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String ref = DuckDbIdentifiers.quote(refs.duckDbTable(params.longValue("referenceTableId")));
        String refKey = "r." + DuckDbIdentifiers.quote(params.text("referenceKey"));
        String refValue = "r." + DuckDbIdentifiers.quote(params.text("referenceValue"));
        String key = data.quotedTable() + "." + data.quotedColumn(rule.firstColumn());
        String value = data.quotedTable() + "." + data.quotedColumn(rule.secondColumn());
        var norm = rule.normalization();
        String sameKey = norm.apply(refKey) + " = " + norm.apply(key);
        return SqlFragment.of("EXISTS (SELECT 1 FROM " + ref + " r WHERE " + sameKey + ")"
                + " AND NOT EXISTS (SELECT 1 FROM " + ref + " r WHERE " + sameKey
                + " AND " + norm.apply(refValue) + " IS NOT DISTINCT FROM " + norm.apply(value) + ")");
    }

    @Override
    protected String anomalyColumn(RuleDefinition rule) {
        return rule.secondColumn();
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.secondColumn() + " » ne correspond pas à la valeur de la table de référence.";
    }
}
