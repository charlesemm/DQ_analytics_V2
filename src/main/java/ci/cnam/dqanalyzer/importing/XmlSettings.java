package ci.cnam.dqanalyzer.importing;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Paramètres de lecture d'un fichier XML : le nom de l'élément qui se répète, une occurrence valant
 * une ligne (IMP-08). Ses sous-éléments et ses attributs deviennent les colonnes.
 *
 * @param rowElement nom de l'élément répété, éventuellement précédé de son chemin
 *     ({@code assures/assure}) ; vide = le premier élément sous la racine, proposé par la détection
 */
public record XmlSettings(String rowElement) implements ImportSettings {

    public XmlSettings {
        rowElement = rowElement == null || rowElement.isBlank() ? "" : rowElement.trim();
    }

    /** Élément répété encore inconnu : il sera déduit du fichier à l'ouverture (IMP-07). */
    public static XmlSettings undetermined() {
        return new XmlSettings("");
    }

    @JsonIgnore
    @Override
    public ImportFormat format() {
        return ImportFormat.XML;
    }

    /** Les noms de colonnes viennent des sous-éléments, jamais d'une ligne d'en-tête. */
    @JsonIgnore
    @Override
    public boolean hasHeader() {
        return false;
    }

    /** Dernier segment du chemin : le nom de l'élément lui-même. */
    @JsonIgnore
    public String elementName() {
        int separator = rowElement.lastIndexOf('/');
        return separator < 0 ? rowElement : rowElement.substring(separator + 1);
    }

    public XmlSettings withRowElement(String newRowElement) {
        return new XmlSettings(newRowElement);
    }
}
