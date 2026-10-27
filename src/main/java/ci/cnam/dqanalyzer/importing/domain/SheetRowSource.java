package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import java.util.Collections;
import java.util.List;

/**
 * Ce que les trois tableurs (.xlsx, .xls, .ods) ont en commun : trouver la ligne d'en-tête demandée,
 * en tirer les noms de colonnes, puis servir les lignes de données (IMP-02). Chaque format n'a plus
 * qu'à dire comment se lit une ligne.
 *
 * <p>Conventions propres au tableur, les mêmes pour les trois formats :
 *
 * <ul>
 *   <li>une ligne sans aucune valeur n'est pas une donnée : elle est ignorée, comme une ligne vide
 *       dans un fichier texte, mais les numéros de ligne restent ceux du tableur ;
 *   <li>une cellule vide en fin de ligne n'existe pas dans le fichier : elle ne rend pas la ligne
 *       incomplète ; seule une cellule au-delà de la dernière colonne de l'en-tête compte comme un
 *       débordement.
 * </ul>
 */
abstract class SheetRowSource implements RowSource {

    private List<String> columns = List.of();

    /** Première ligne de données, déjà lue pour en déduire les colonnes d'une feuille sans en-tête. */
    private Row pending;

    private Row current;

    /** Une ligne du tableur : son numéro d'origine et ses cellules, rangées à leur position. */
    protected record Row(long number, List<String> values) {}

    /**
     * Ligne suivante du tableur, ou {@code null} à la fin de la feuille. Les lignes entièrement vides
     * sont déjà écartées par l'implémentation.
     *
     * @throws ImportRejectedException si la ligne est illisible
     */
    protected abstract Row nextRow();

    /**
     * Lit l'en-tête, ou la première ligne de données si la feuille n'en a pas. À appeler une fois, à
     * l'ouverture, par l'implémentation.
     *
     * @throws ImportRejectedException si la feuille est vide ou si la ligne d'en-tête est absente
     */
    protected final void readHeader(SheetSettings settings) {
        Row row = nextRow();
        if (row == null) {
            throw new ImportRejectedException("import.error.fichier-vide");
        }
        if (!settings.hasHeader()) {
            columns = HeaderNames.normalize(Collections.nCopies(row.values().size(), ""));
            pending = row;
            return;
        }
        while (row.number() < settings.headerRow()) {
            row = nextRow();
            if (row == null) {
                throw new ImportRejectedException("import.error.ligne-entete-introuvable", settings.headerRow());
            }
        }
        if (row.number() > settings.headerRow()) {
            // La ligne existe peut-être dans le tableur, mais elle est vide : sans nom de colonne,
            // l'analyste ne pourrait pas écrire ses règles.
            throw new ImportRejectedException("import.error.ligne-entete-introuvable", settings.headerRow());
        }
        columns = HeaderNames.normalize(row.values());
    }

    @Override
    public final List<String> columns() {
        return columns;
    }

    @Override
    public final boolean next() {
        if (pending != null) {
            current = pending;
            pending = null;
            return true;
        }
        current = nextRow();
        return current != null;
    }

    @Override
    public final long rowNumber() {
        return current.number();
    }

    @Override
    public final int fieldCount() {
        return Math.max(columns.size(), current.values().size());
    }

    @Override
    public final String field(int index) {
        var values = current.values();
        if (index >= values.size()) {
            return null;
        }
        String value = values.get(index);
        return value == null || value.isEmpty() ? null : value;
    }

    /** Ligne courante, pour les implémentations qui mesurent l'avancement. */
    protected final Row currentRow() {
        return current;
    }

    /** Vrai si la ligne ne porte aucune valeur : elle ne sera pas servie comme donnée. */
    protected static boolean isEmpty(List<String> values) {
        return values.stream().allMatch(value -> value == null || value.isBlank());
    }
}
