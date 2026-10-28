package ci.cnam.dqanalyzer.templates;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Association automatique des colonnes attendues par un modèle avec celles d'un fichier (MOD-02) :
 * « Date de naissance », « date_de_naissance » et « DATE DE NAISSANCE » désignent la même colonne.
 * L'analyste corrige ensuite à la main ce qui n'a pas été reconnu.
 */
public final class ColumnMatcher {

    private ColumnMatcher() {}

    /** @return colonne attendue → colonne du fichier, ou null si rien ne correspond */
    public static Map<String, String> match(List<String> expected, List<String> fileColumns) {
        var byKey = new HashMap<String, String>();
        for (String column : fileColumns) {
            byKey.putIfAbsent(key(column), column);
        }
        var result = new LinkedHashMap<String, String>();
        for (String column : expected) {
            result.put(column, byKey.get(key(column)));
        }
        return result;
    }

    /** Minuscules, sans accents, et tout ce qui n'est ni lettre ni chiffre retiré. */
    static String key(String name) {
        String noAccents = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
