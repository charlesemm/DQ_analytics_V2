package ci.cnam.dqanalyzer.importing;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Paramètres de lecture d'un fichier : ce que la détection automatique propose (IMP-07) et que
 * l'analyste peut corriger (IMP-02, IMP-03) avant le chargement. Chaque famille de format a les
 * siens ; ils sont enregistrés tels quels avec le fichier source, et le seront dans le modèle
 * d'analyse pour ne pas les ressaisir au fichier suivant (IMP-08).
 *
 * <p>Le type sert de discriminant {@code @type} dans le JSON enregistré en base : les paramètres se
 * relisent sans deviner de quel format ils venaient.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DelimitedSettings.class, name = "DELIMITED"),
    @JsonSubTypes.Type(value = SheetSettings.class, name = "SHEET"),
    @JsonSubTypes.Type(value = FixedWidthSettings.class, name = "FIXED_WIDTH"),
    @JsonSubTypes.Type(value = XmlSettings.class, name = "XML"),
    @JsonSubTypes.Type(value = JsonSettings.class, name = "JSON"),
    @JsonSubTypes.Type(value = ParquetSettings.class, name = "PARQUET")
})
public sealed interface ImportSettings
        permits DelimitedSettings, SheetSettings, FixedWidthSettings, XmlSettings, JsonSettings, ParquetSettings {

    /** Format que ces paramètres décrivent. */
    ImportFormat format();

    /** Vrai si la première ligne lue porte les noms de colonnes plutôt que des données. */
    boolean hasHeader();
}
