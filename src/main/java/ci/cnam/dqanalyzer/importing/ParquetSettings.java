package ci.cnam.dqanalyzer.importing;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Paramètres de lecture d'un fichier Parquet : il n'y en a aucun (CDC §5.1 bis). Le format porte
 * lui-même le nom de chaque colonne, et il n'y a ni encodage, ni séparateur, ni feuille à choisir. Le
 * type existe quand même, pour que tous les formats se décrivent de la même façon et que les
 * paramètres enregistrés en base se relisent sans cas particulier.
 */
public record ParquetSettings() implements ImportSettings {

    @JsonIgnore
    @Override
    public ImportFormat format() {
        return ImportFormat.PARQUET;
    }

    /** Les noms de colonnes viennent du schéma du fichier : il n'y a pas de ligne d'en-tête à lire. */
    @JsonIgnore
    @Override
    public boolean hasHeader() {
        return false;
    }
}
