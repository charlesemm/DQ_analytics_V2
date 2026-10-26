package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * ACT-01 — Âge maximal de la donnée : « dernière mise à jour de moins de 12 mois ».
 * Ne contrôle que les dates lisibles : une date mal écrite relève de VAL-06.
 * La date du jour vient d'une {@link Clock} : les tests la fixent, le résultat ne change pas d'un jour à l'autre.
 */
@Component
public class Act01MaxAgeEvaluator extends RowPredicateEvaluator {

    private final Clock clock;

    public Act01MaxAgeEvaluator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return "ACT-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.TIMELINESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("maxAge", ParamType.INTEGER, true, "param.maxAge"),
                new Param("unit", ParamType.TEXT, true, "param.ageUnit"),
                new Param("dateFormat", ParamType.TEXT, false, "param.dateFormat"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return SqlFragment.of(Sql.date(data.quotedColumn(rule.firstColumn())) + " IS NOT NULL", format(rule));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        long maxAge = params.longValue("maxAge");
        LocalDate today = LocalDate.now(clock);
        LocalDate limit =
                switch (params.text("unit").toLowerCase()) {
                    case "jours", "days" -> today.minusDays(maxAge);
                    case "mois", "months" -> today.minusMonths(maxAge);
                    case "ans", "years" -> today.minusYears(maxAge);
                    default ->
                        throw new RuleEvaluationException(
                                "Règle " + rule.ruleCode() + " : unité inconnue (jours, mois, ans)");
                };
        return new SqlFragment(
                Sql.date(data.quotedColumn(rule.firstColumn())) + " < CAST(? AS DATE)",
                List.of(format(rule), limit.toString()));
    }

    private static String format(RuleDefinition rule) {
        return DateFormats.toDuckDb(RuleParams.of(rule).textOrDefault("dateFormat", DateFormats.DEFAULT));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        var params = RuleParams.of(rule);
        return "« " + rule.firstColumn() + " » date de plus de " + params.longValue("maxAge") + " "
                + params.text("unit") + ".";
    }
}
