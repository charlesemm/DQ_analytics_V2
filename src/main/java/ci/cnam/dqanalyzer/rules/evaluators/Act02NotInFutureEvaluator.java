package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/** ACT-02 — Date non future : « pas de date de naissance dans le futur ». */
@Component
public class Act02NotInFutureEvaluator extends RowPredicateEvaluator {

    private final Clock clock;

    public Act02NotInFutureEvaluator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return "ACT-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.TIMELINESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("dateFormat", ParamType.TEXT, false, "param.dateFormat"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return SqlFragment.of(Sql.date(data.quotedColumn(rule.firstColumn())) + " IS NOT NULL", format(rule));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        return new SqlFragment(
                Sql.date(data.quotedColumn(rule.firstColumn())) + " > CAST(? AS DATE)",
                List.of(format(rule), LocalDate.now(clock).toString()));
    }

    private static String format(RuleDefinition rule) {
        return DateFormats.toDuckDb(RuleParams.of(rule).textOrDefault("dateFormat", DateFormats.DEFAULT));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » contient une date dans le futur.";
    }
}
