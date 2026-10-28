package ci.cnam.dqanalyzer.rules;

import java.time.LocalDate;
import java.util.List;

/**
 * Une règle telle que la liste du catalogue l'affiche (REG-06) : la version courante, son
 * propriétaire métier, sa validation et son auteur. Distinct de {@link RuleDefinition}, qui ne porte
 * que ce dont le moteur a besoin pour exécuter la règle.
 */
public record RuleSummary(
        String ruleCode,
        int version,
        String name,
        String type,
        Dimension dimension,
        Severity severity,
        List<String> targetColumns,
        boolean active,
        String businessOwner,
        String validatedBy,
        LocalDate validatedOn,
        String authorName,
        String changeComment) {

    public RuleSummary {
        targetColumns = List.copyOf(targetColumns);
    }

    /** Une règle est validée dès qu'une direction métier a daté son accord (gouvernance fédérée). */
    public boolean validated() {
        return validatedOn != null;
    }
}
