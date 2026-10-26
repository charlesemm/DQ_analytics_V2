package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.rules.Dataset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Rend utilisables les en-têtes d'un fichier réel : espaces autour du nom, colonnes sans titre,
 * doublons. Deux contraintes dictent les règles ci-dessous :
 *
 * <ul>
 *   <li>DuckDB ne distingue pas la casse des identifiants : « Nom » et « nom » entreraient en conflit ;
 *   <li>la table importée contient déjà la colonne technique {@code _row_num} (n° de ligne d'origine).
 * </ul>
 */
public final class HeaderNames {

    private static final String UNNAMED_PREFIX = "colonne_";

    /** Marque d'ordre d'octets, parfois collée au premier en-tête par Excel. */
    private static final String BOM = "﻿";

    private HeaderNames() {}

    /** Renvoie autant de noms que d'en-têtes reçus, non vides et tous distincts. */
    public static List<String> normalize(List<String> rawHeaders) {
        if (rawHeaders == null || rawHeaders.isEmpty()) {
            throw new ImportRejectedException("import.error.entete-vide");
        }
        var taken = new HashSet<String>();
        taken.add(Dataset.ROW_NUM_COLUMN.toLowerCase(Locale.ROOT));

        var names = new ArrayList<String>(rawHeaders.size());
        for (int position = 0; position < rawHeaders.size(); position++) {
            String base = clean(rawHeaders.get(position), position);
            String name = base;
            int suffix = 2;
            while (!taken.add(name.toLowerCase(Locale.ROOT))) {
                name = base + "_" + suffix++;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static String clean(String rawHeader, int position) {
        String cleaned = rawHeader == null
                ? ""
                : rawHeader.replace(BOM, "").replace("\0", "").trim();
        return cleaned.isEmpty() ? UNNAMED_PREFIX + (position + 1) : cleaned;
    }
}
