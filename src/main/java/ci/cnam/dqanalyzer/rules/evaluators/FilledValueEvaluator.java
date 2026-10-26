package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;

/**
 * Base des règles qui contrôlent le contenu d'une cellule (validité, exactitude, actualité…).
 * Elles ne contrôlent que les cellules renseignées : une cellule vide relève de CMP-01, pas d'elles.
 * Sans cela, une colonne à moitié vide compterait deux fois ses cellules vides en anomalie.
 */
abstract class FilledValueEvaluator extends RowPredicateEvaluator {

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return Sql.filled(data.quotedColumn(rule.firstColumn()));
    }

    /** Valeur de la colonne après la normalisation choisie dans la règle (espaces, majuscules, accents). */
    protected String normalizedValue(Dataset data, RuleDefinition rule) {
        return rule.normalization().apply(data.quotedColumn(rule.firstColumn()));
    }
}
