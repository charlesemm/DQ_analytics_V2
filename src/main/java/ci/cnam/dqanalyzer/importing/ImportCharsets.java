package ci.cnam.dqanalyzer.importing;

import java.util.List;
import java.util.Locale;

/**
 * Encodages acceptés pour les fichiers texte (IMP-03), volontairement limités à ceux du CDC : tout ce
 * qui sort de cette liste est refusé plutôt que deviné.
 */
public final class ImportCharsets {

    /** UTF-8 et Windows-1252 (CDC IMP-03) ; UTF-16 n'est reconnu que par sa marque d'ordre d'octets. */
    public static final List<String> SUPPORTED = List.of("UTF-8", "windows-1252", "UTF-16LE", "UTF-16BE");

    private ImportCharsets() {}

    /**
     * Renvoie l'orthographe de référence de l'encodage.
     *
     * @throws ImportRejectedException si l'encodage n'est pas dans la liste
     */
    public static String canonical(String name) {
        String candidate = name == null ? "" : name.trim();
        for (String supported : SUPPORTED) {
            if (supported.toLowerCase(Locale.ROOT).equals(candidate.toLowerCase(Locale.ROOT))) {
                return supported;
            }
        }
        throw new ImportRejectedException("import.error.encodage-inconnu");
    }
}
