package ci.cnam.dqanalyzer.importing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Les paramètres d'import sont enregistrés en JSON avec le fichier source, et le seront dans le
 * modèle d'analyse (IMP-08) : ils doivent se relire tels quels, sans savoir d'avance de quel format
 * ils venaient.
 */
class ImportSettingsJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void relit_les_parametres_d_un_fichier_texte() {
        var settings = new DelimitedSettings("windows-1252", ',', '"', true);

        assertThat(relire(settings)).isEqualTo(settings);
    }

    @Test
    void relit_les_parametres_d_un_tableur() {
        var settings = new SheetSettings(ImportFormat.XLSX, "Assurés", 3);

        assertThat(relire(settings)).isEqualTo(settings);
    }

    @Test
    void relit_les_parametres_d_un_fichier_parquet() {
        assertThat(relire(new ParquetSettings())).isEqualTo(new ParquetSettings());
    }

    @Test
    void le_json_porte_le_type_des_parametres() {
        assertThat(json.writeValueAsString(new ParquetSettings())).contains("\"@type\":\"PARQUET\"");
    }

    private ImportSettings relire(ImportSettings settings) {
        return json.readValue(json.writeValueAsString(settings), ImportSettings.class);
    }
}
