package ci.cnam.dqanalyzer.rules.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Essai d'une règle sur un fichier déjà chargé (REG-03) : comptages et anomalies affichés, rien
 * d'enregistré, et refus pour un Lecteur (SEC-02).
 *
 * <p>Comme pour l'écran de profil, le fichier est enregistré tel que l'import l'aurait laissé — une
 * ligne {@code source_file} au statut {@code DONE} et une base DuckDB sur le disque — pour exercer
 * le vrai {@code ImportService.tableOf}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleTestControllerIT {

    private static final String TABLE = "ds_essai";

    @TempDir
    static Path directory;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long fileId;

    @BeforeEach
    void prepareUnFichierCharge() throws Exception {
        Path database = directory.resolve("essai.duckdb");
        if (!Files.exists(database)) {
            Path csv = directory.resolve("assures-essai.csv");
            // La troisième ligne de données n'a pas de sexe : c'est l'anomalie attendue (CMP-01).
            Files.writeString(
                    csv,
                    """
                    matricule;sexe;ville
                    CI000001;M;Abidjan
                    CI000002;F;Bouaké
                    CI000003;;Korhogo
                    """);
            try (var connection = new DuckDbDatabases().open(database);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"" + TABLE + "\" AS SELECT row_number() OVER () + 1 AS \"_row_num\","
                        + " * FROM read_csv('"
                        + csv.toAbsolutePath().toString().replace('\\', '/')
                        + "', delim=';', header=true, all_varchar=true)");
            }
        }
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-essai', 'Analyste (essai)', 'analyste-essai@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-essai', 'Lecteur (essai)', 'lecteur-essai@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
        long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-essai'", Long.class);
        fileId = jdbc.queryForObject(
                """
                INSERT INTO source_file (original_name, size_bytes, sha256, format, import_settings,
                                         storage_path, uploaded_by, import_status, import_progress,
                                         row_count, column_count, ragged_row_count, duckdb_path, table_name)
                VALUES ('assures-essai.csv', 120, repeat('b', 64), 'DELIMITED', '{}'::jsonb,
                        '/tmp/assures-essai.csv', ?, 'DONE', 100, 3, 3, 0, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                database.toString(),
                TABLE);
    }

    @Test
    @WithMockUser(username = "analyste-essai", roles = "ANALYST")
    void l_essai_compte_les_lignes_et_montre_l_anomalie_sans_rien_enregistrer() throws Exception {
        int reglesAvant = jdbc.queryForObject("SELECT count(*) FROM dq_rule", Integer.class);

        mvc.perform(post("/regles/essai")
                        .param("testFileId", String.valueOf(fileId))
                        .param("name", "Sexe obligatoire")
                        .param("type", "CMP-01")
                        .param("targetColumns", "sexe")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "100")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("rules/fragments/test-result"))
                .andExpect(content().string(containsString("Anomalies trouvées : 1")))
                // La ligne 4 du fichier : en-tête = ligne 1.
                .andExpect(content().string(containsString("<td>4</td>")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dq_rule", Integer.class))
                .isEqualTo(reglesAvant);
    }

    @Test
    @WithMockUser(username = "analyste-essai", roles = "ANALYST")
    void un_essai_sans_anomalie_le_dit() throws Exception {
        mvc.perform(post("/regles/essai")
                        .param("testFileId", String.valueOf(fileId))
                        .param("name", "Matricule obligatoire")
                        .param("type", "CMP-01")
                        .param("targetColumns", "matricule")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "100")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Aucune anomalie")));
    }

    @Test
    @WithMockUser(username = "analyste-essai", roles = "ANALYST")
    void une_colonne_absente_du_fichier_donne_un_message_en_francais() throws Exception {
        mvc.perform(post("/regles/essai")
                        .param("testFileId", String.valueOf(fileId))
                        .param("name", "Colonne inconnue")
                        .param("type", "CMP-01")
                        .param("targetColumns", "commune")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "100")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("existe pas dans le fichier")));
    }

    @Test
    @WithMockUser(username = "lecteur-essai", roles = "READER")
    void un_lecteur_ne_peut_pas_lancer_un_essai() throws Exception {
        mvc.perform(post("/regles/essai")
                        .param("testFileId", String.valueOf(fileId))
                        .param("name", "Essai interdit")
                        .param("type", "CMP-01")
                        .param("targetColumns", "sexe")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "100")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
