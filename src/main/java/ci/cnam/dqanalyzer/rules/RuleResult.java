package ci.cnam.dqanalyzer.rules;

/**
 * Comptages bruts produits par un évaluateur. Le score et le statut sont calculés ailleurs (Scoring).
 *
 * @param checkedRows lignes concernées par la règle (hors lignes exclues par une condition)
 * @param conformingRows lignes conformes parmi les lignes contrôlées
 * @param duplicateGroups nombre de groupes de doublons, renseigné uniquement pour les règles d'unicité
 */
public record RuleResult(long checkedRows, long conformingRows, Long duplicateGroups) {

    public RuleResult {
        if (checkedRows < 0 || conformingRows < 0 || conformingRows > checkedRows) {
            throw new IllegalArgumentException(
                    "Comptages incohérents : contrôlées=" + checkedRows + ", conformes=" + conformingRows);
        }
    }

    public static RuleResult of(long checkedRows, long conformingRows) {
        return new RuleResult(checkedRows, conformingRows, null);
    }

    public long anomalyRows() {
        return checkedRows - conformingRows;
    }
}
