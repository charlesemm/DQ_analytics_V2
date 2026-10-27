package ci.cnam.dqanalyzer.importing;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Paramètres de lecture d'un fichier JSON : le chemin du tableau d'objets à lire, un objet valant
 * une ligne (IMP-08). Les objets imbriqués sont aplatis en colonnes {@code adresse.ville}.
 *
 * @param arrayPath chemin du tableau, par exemple {@code donnees/assures} ; vide = le tableau
 *     principal du fichier, ou un objet par ligne (.jsonl, .ndjson)
 */
public record JsonSettings(String arrayPath) implements ImportSettings {

    public JsonSettings {
        arrayPath = arrayPath == null || arrayPath.isBlank() ? "" : arrayPath.trim();
    }

    /** Le tableau principal du fichier : le cas courant, et celui des fichiers ligne par ligne. */
    public static JsonSettings root() {
        return new JsonSettings("");
    }

    @JsonIgnore
    @Override
    public ImportFormat format() {
        return ImportFormat.JSON;
    }

    /** Les noms de colonnes viennent des propriétés des objets, jamais d'une ligne d'en-tête. */
    @JsonIgnore
    @Override
    public boolean hasHeader() {
        return false;
    }

    public JsonSettings withArrayPath(String newArrayPath) {
        return new JsonSettings(newArrayPath);
    }
}
