package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ce que le XML et le JSON ont en commun : un fichier y est une suite d'enregistrements nommés
 * (sous-éléments, propriétés), et non un tableau. Les colonnes sont donc celles du premier
 * enregistrement, dans son ordre ; les suivants s'y rangent par nom.
 *
 * <p>Deux conséquences, à dire à l'analyste dans l'écran d'import :
 *
 * <ul>
 *   <li>une propriété absente d'un enregistrement vaut « non renseigné », comme une cellule vide ;
 *   <li>une propriété qu'aucun autre enregistrement ne porte n'est pas perdue en silence : elle
 *       compte comme un débordement, et le nombre de lignes concernées est affiché à la fin de
 *       l'import.
 * </ul>
 *
 * <p>Le numéro de ligne est le rang de l'enregistrement dans le fichier : le 3ᵉ objet d'un tableau
 * JSON est la ligne 3. C'est le repère que l'analyste peut retrouver dans son fichier d'origine.
 */
abstract class RecordRowSource implements RowSource {

    private List<String> columns = List.of();

    /** Noms des propriétés tels qu'ils sont écrits dans le fichier, dans l'ordre des colonnes. */
    private List<String> originalNames = List.of();

    private Map<String, String> pending;
    private Map<String, String> current;
    private long number;

    /**
     * Enregistrement suivant, par nom de propriété et dans l'ordre du fichier, ou {@code null} à la
     * fin. Les valeurs vides y sont déjà ramenées à {@code null}.
     *
     * @throws ImportRejectedException si le fichier est illisible
     */
    protected abstract Map<String, String> nextRecord();

    /**
     * Lit le premier enregistrement, qui donne les colonnes. À appeler une fois, à l'ouverture.
     *
     * @throws ImportRejectedException si le fichier ne contient aucun enregistrement
     */
    protected final void readFirstRecord() {
        var first = nextRecord();
        if (first == null) {
            throw new ImportRejectedException("import.error.aucun-enregistrement");
        }
        originalNames = List.copyOf(new ArrayList<>(first.keySet()));
        columns = HeaderNames.normalize(originalNames);
        pending = first;
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
            number++;
            return true;
        }
        current = nextRecord();
        if (current == null) {
            return false;
        }
        number++;
        return true;
    }

    @Override
    public final long rowNumber() {
        return number;
    }

    @Override
    public final int fieldCount() {
        // Une propriété que le premier enregistrement ne portait pas n'a pas de colonne où aller :
        // elle fait déborder la ligne, et l'analyste en sera averti.
        int unknown = 0;
        for (String name : current.keySet()) {
            if (!originalNames.contains(name)) {
                unknown++;
            }
        }
        return columns.size() + unknown;
    }

    @Override
    public final String field(int index) {
        if (index >= columns.size()) {
            return null;
        }
        // La colonne a pu être renommée (doublon, nom vide) : la valeur se cherche au nom d'origine.
        String value = current.get(originalNames.get(index));
        return value == null || value.isEmpty() ? null : value;
    }
}
