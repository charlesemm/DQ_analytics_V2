package ci.cnam.dqanalyzer.importing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aperçu d'un fichier avant son chargement (IMP-04) : ce que l'analyste voit pour juger si les
 * paramètres de lecture sont les bons — séparateur, encodage, feuille, ligne d'en-tête, gabarit.
 *
 * <p>C'est le moment où une erreur de paramètre se voit : des colonnes décalées, des accents cassés,
 * une ligne d'en-tête prise pour une donnée. Mieux vaut le découvrir sur 50 lignes que sur 2 Go.
 *
 * @param columns colonnes telles qu'elles seront créées dans la table
 * @param rows les {@value #ROWS} premières lignes de données, avec leur n° de ligne d'origine
 * @param truncated vrai si le fichier contient plus de lignes que celles montrées
 */
public record ImportPreview(List<String> columns, List<PreviewRow> rows, boolean truncated) {

    /** Nombre de lignes montrées (CDC IMP-04). */
    public static final int ROWS = 50;

    public ImportPreview {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    /**
     * Une ligne de l'aperçu.
     *
     * @param number n° de la ligne dans le fichier d'origine
     * @param values valeurs, dans l'ordre des colonnes ; {@code null} pour une valeur absente
     */
    public record PreviewRow(long number, List<String> values) {

        public PreviewRow {
            // Une valeur absente est null : la liste ne peut donc pas être une List.copyOf.
            values = Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
