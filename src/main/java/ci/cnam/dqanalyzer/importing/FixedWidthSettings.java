package ci.cnam.dqanalyzer.importing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Paramètres de lecture d'un fichier à largeur fixe : l'encodage et le gabarit, c'est-à-dire la
 * position et la longueur de chaque colonne (IMP-08). Le gabarit est la seule façon de découper ces
 * fichiers, qui n'ont ni séparateur ni structure : il est donc enregistré avec le fichier, puis dans
 * le modèle d'analyse, pour être réutilisé à l'extraction suivante.
 *
 * @param fields colonnes du gabarit, dans l'ordre ; vide tant que l'analyste ne l'a pas rempli
 * @param hasHeader vrai si la première ligne porte les noms de colonnes plutôt que des données
 */
public record FixedWidthSettings(String charsetName, List<FixedWidthField> fields, boolean hasHeader)
        implements ImportSettings {

    public FixedWidthSettings {
        charsetName = ImportCharsets.canonical(charsetName);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** Gabarit vide, à compléter par l'analyste : un tel fichier ne peut pas encore être chargé. */
    public static FixedWidthSettings empty(String charsetName) {
        return new FixedWidthSettings(charsetName, List.of(), true);
    }

    @JsonIgnore
    @Override
    public ImportFormat format() {
        return ImportFormat.FIXED_WIDTH;
    }

    public Charset charset() {
        return Charset.forName(charsetName);
    }

    public FixedWidthSettings withFields(List<FixedWidthField> newFields) {
        return new FixedWidthSettings(charsetName, newFields, hasHeader);
    }

    public FixedWidthSettings withCharsetName(String newCharsetName) {
        return new FixedWidthSettings(newCharsetName, fields, hasHeader);
    }

    public FixedWidthSettings withHasHeader(boolean newHasHeader) {
        return new FixedWidthSettings(charsetName, fields, newHasHeader);
    }
}
