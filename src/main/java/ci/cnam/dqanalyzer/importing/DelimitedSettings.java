package ci.cnam.dqanalyzer.importing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Paramètres de lecture d'un fichier texte délimité (IMP-03). Proposés par la détection
 * automatique (IMP-07), puis corrigeables par l'analyste : d'où les méthodes {@code with*}.
 *
 * <p>Les encodages et séparateurs sont volontairement limités à ceux du CDC : tout ce qui sort de ces
 * listes est refusé plutôt que deviné.
 */
public record DelimitedSettings(String charsetName, char delimiter, char quote, boolean hasHeader)
        implements ImportSettings {

    /** UTF-8 et Windows-1252 (CDC IMP-03) ; UTF-16 n'est reconnu que par sa marque d'ordre d'octets. */
    public static final List<String> SUPPORTED_CHARSETS = List.of("UTF-8", "windows-1252", "UTF-16LE", "UTF-16BE");

    /** Point-virgule, virgule, tabulation (CDC IMP-03). */
    public static final List<Character> SUPPORTED_DELIMITERS = List.of(';', ',', '\t');

    private static final Set<Character> SUPPORTED_QUOTES = Set.of('"', '\'');

    public DelimitedSettings {
        charsetName = canonicalCharsetName(charsetName);
        if (!SUPPORTED_DELIMITERS.contains(delimiter)) {
            throw new ImportRejectedException("import.error.separateur-inconnu");
        }
        if (!SUPPORTED_QUOTES.contains(quote) || quote == delimiter) {
            throw new ImportRejectedException("import.error.guillemet-inconnu");
        }
    }

    @JsonIgnore
    @Override
    public ImportFormat format() {
        return ImportFormat.DELIMITED;
    }

    public Charset charset() {
        return Charset.forName(charsetName);
    }

    public DelimitedSettings withCharsetName(String newCharsetName) {
        return new DelimitedSettings(newCharsetName, delimiter, quote, hasHeader);
    }

    public DelimitedSettings withDelimiter(char newDelimiter) {
        return new DelimitedSettings(charsetName, newDelimiter, quote, hasHeader);
    }

    public DelimitedSettings withHasHeader(boolean newHasHeader) {
        return new DelimitedSettings(charsetName, delimiter, quote, newHasHeader);
    }

    /** Renvoie l'orthographe de référence de l'encodage ; refuse ce qui n'est pas dans la liste. */
    private static String canonicalCharsetName(String name) {
        String candidate = name == null ? "" : name.trim();
        for (String supported : SUPPORTED_CHARSETS) {
            if (supported.toLowerCase(Locale.ROOT).equals(candidate.toLowerCase(Locale.ROOT))) {
                return supported;
            }
        }
        throw new ImportRejectedException("import.error.encodage-inconnu");
    }
}
