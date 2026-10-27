package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.FixedWidthField;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.util.ArrayList;
import java.util.List;

/**
 * Écriture courte d'un gabarit de fichier à largeur fixe, telle qu'elle circule entre l'écran et le
 * serveur : {@code num_secu:1:13,nom:14:30,ville:44:25}. Un nom vide laisse la colonne prendre son
 * nom dans la ligne d'en-tête du fichier : {@code :1:13}.
 */
final class FixedWidthTemplates {

    private static final String FIELD_SEPARATOR = ",";
    private static final String PART_SEPARATOR = ":";
    private static final int PARTS = 3;

    private FixedWidthTemplates() {}

    /**
     * @throws ImportRejectedException si le gabarit est mal écrit
     */
    static List<FixedWidthField> parse(String template) {
        var fields = new ArrayList<FixedWidthField>();
        for (String declaration : template.split(FIELD_SEPARATOR)) {
            if (declaration.isBlank()) {
                continue;
            }
            var parts = declaration.split(PART_SEPARATOR, -1);
            if (parts.length != PARTS) {
                throw new ImportRejectedException("import.error.gabarit-illisible", declaration.trim());
            }
            fields.add(new FixedWidthField(parts[0], number(parts[1], declaration), number(parts[2], declaration)));
        }
        if (fields.isEmpty()) {
            throw new ImportRejectedException("import.error.gabarit-absent");
        }
        return List.copyOf(fields);
    }

    private static int number(String value, String declaration) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ImportRejectedException("import.error.gabarit-illisible", declaration.trim());
        }
    }
}
