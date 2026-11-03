package ci.cnam.dqanalyzer.execution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.RuleCatalogService;
import ci.cnam.dqanalyzer.rules.Severity;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import ci.cnam.dqanalyzer.templates.TemplateService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Écrans de lancement et de suivi d'une analyse (EXE-01, EXE-02) : le lancement rend la main tout de
 * suite, le suivi affiche l'avancement puis le résultat, et un Lecteur ne peut pas lancer (SEC-02).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisControllerIT {

    private static final String TABLE = "ds_ecran_analyse";
    private static final String OWNER = "Direction des prestations (écran analyse)";
    private static final String TEMPLATE = "Modèle d'analyse (test écran)";

    @TempDir
    static Path directory;

    @Autowired
    MockMvc mvc;

    @Autowired
    TemplateService templates;

    @Autowired
    RuleCatalogService catalog;

    @Autowired
    JdbcTemplate jdbc;

    private long sourceFileId;
    private long templateId;

    @BeforeEach
    void preparerFichierEtModele() throws Exception {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-ecran-exec', 'Analyste (écran analyse)', 'analyste-ecran-exec@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-ecran-exec', 'Lecteur (écran analyse)', 'lecteur-ecran-exec@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
        long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-ecran-exec'", Long.class);

        Path database = directory.resolve("ecran-analyse.duckdb");
        if (!Files.exists(database)) {
            Path csv = directory.resolve("assures.csv");
            Files.copy(
                    Objects.requireNonNull(
                            getClass().getClassLoader().getResourceAsStream("datasets/recette_assures.csv")),
                    csv);
            try (var connection = new DuckDbDatabases().open(database);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"" + TABLE + "\" AS SELECT row_number() OVER () + 1 AS \"_row_num\","
                        + " * FROM read_csv('"
                        + csv.toAbsolutePath().toString().replace('\\', '/')
                        + "', delim=';', header=true, all_varchar=true)");
            }
        }
        sourceFileId = jdbc.queryForObject(
                """
                INSERT INTO source_file (original_name, size_bytes, sha256, format, import_settings,
                                         storage_path, uploaded_by, import_status, import_progress,
                                         row_count, column_count, ragged_row_count, duckdb_path, table_name)
                VALUES ('assures-ecran.csv', 600, repeat('e', 64), 'DELIMITED', '{}'::jsonb,
                        '/tmp/assures-ecran.csv', ?, 'DONE', 100, 12, 6, 0, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                database.toString(),
                TABLE);

        jdbc.update(
                "DELETE FROM analysis_run WHERE template_id IN (SELECT id FROM analysis_template WHERE name = ?)",
                TEMPLATE);
        jdbc.update("DELETE FROM analysis_template WHERE name = ?", TEMPLATE);
        jdbc.update("DELETE FROM dq_rule WHERE business_owner = ?", OWNER);
        String code = catalog.create(
                new RuleCatalogService.RuleForm(
                        "Sexe obligatoire (écran)",
                        null,
                        "CMP-01",
                        List.of("Sexe"),
                        Map.of(),
                        Normalization.NONE,
                        Severity.MAJOR,
                        100.0,
                        null,
                        OWNER,
                        "Création"),
                userId);
        templateId = templates.create(
                new TemplateService.TemplateForm(
                        TEMPLATE, "Modèle de test", List.of("Sexe"), Map.of(Dimension.COMPLETENESS, 1), List.of(code)),
                userId);
        templates.saveColumnMapping(templateId, Map.of("Sexe", "sexe"));
    }

    @Test
    @WithMockUser(username = "analyste-ecran-exec", roles = "ANALYST")
    void l_ecran_de_lancement_propose_les_fichiers_et_rappelle_l_association() throws Exception {
        mvc.perform(get("/analyses/nouvelle").param("templateId", String.valueOf(templateId)))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/new"))
                .andExpect(content().string(containsString("assures-ecran.csv")))
                // L'apostrophe du nom est echappee dans le HTML : on cherche la partie sans apostrophe.
                .andExpect(content().string(containsString("analyse (test écran)")))
                // L'association Sexe → sexe est rappelée avant de lancer.
                .andExpect(content().string(containsString("Sexe")));
    }

    @Test
    @WithMockUser(username = "analyste-ecran-exec", roles = "ANALYST")
    void lancer_redirige_vers_le_suivi_et_l_analyse_se_termine() throws Exception {
        mvc.perform(post("/analyses")
                        .param("sourceFileId", String.valueOf(sourceFileId))
                        .param("templateId", String.valueOf(templateId))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/analyses/*/suivi"));

        long runId =
                jdbc.queryForObject("SELECT max(id) FROM analysis_run WHERE template_id = ?", Long.class, templateId);
        attendreLaFin(runId);

        mvc.perform(get("/analyses/{id}/progression", runId))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/fragments/progress"))
                // Terminée : plus de déclencheur HTMX, et le résultat est affiché.
                .andExpect(content().string(not(containsString("every 2s"))))
                .andExpect(content().string(containsString("Alerte")))
                .andExpect(content().string(containsString("Score global")));
    }

    @Test
    @WithMockUser(username = "analyste-ecran-exec", roles = "ANALYST")
    void le_suivi_d_une_analyse_en_cours_se_recharge_tout_seul() throws Exception {
        long runId = jdbc.queryForObject(
                """
                INSERT INTO analysis_run (source_file_id, template_id, launched_by, status, progress)
                VALUES (?, ?, (SELECT id FROM app_user WHERE login = 'analyste-ecran-exec'), 'RUNNING', 40)
                RETURNING id
                """,
                Long.class,
                sourceFileId,
                templateId);

        mvc.perform(get("/analyses/{id}/suivi", runId))
                .andExpect(status().isOk())
                .andExpect(view().name("execution/follow"))
                .andExpect(content().string(containsString("every 2s")))
                .andExpect(content().string(containsString("40 %")));
    }

    @Test
    @WithMockUser(username = "analyste-ecran-exec", roles = "ANALYST")
    void une_analyse_inconnue_donne_une_page_404_en_francais() throws Exception {
        mvc.perform(get("/analyses/{id}/suivi", 999999))
                .andExpect(status().isNotFound())
                .andExpect(view().name("execution/unavailable"));
    }

    @Test
    @WithMockUser(username = "lecteur-ecran-exec", roles = "READER")
    void un_lecteur_suit_une_analyse_mais_ne_peut_pas_en_lancer() throws Exception {
        long runId = jdbc.queryForObject(
                """
                INSERT INTO analysis_run (source_file_id, template_id, launched_by, status, progress)
                VALUES (?, ?, (SELECT id FROM app_user WHERE login = 'analyste-ecran-exec'), 'RUNNING', 10)
                RETURNING id
                """,
                Long.class,
                sourceFileId,
                templateId);

        mvc.perform(get("/analyses/{id}/suivi", runId)).andExpect(status().isOk());
        mvc.perform(get("/analyses/nouvelle")).andExpect(status().isForbidden());
        mvc.perform(post("/analyses")
                        .param("sourceFileId", String.valueOf(sourceFileId))
                        .param("templateId", String.valueOf(templateId))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private void attendreLaFin(long runId) throws Exception {
        for (int attempt = 0; attempt < 600; attempt++) {
            String status = jdbc.queryForObject("SELECT status FROM analysis_run WHERE id = ?", String.class, runId);
            if ("DONE".equals(status) || "FAILED".equals(status)) {
                assertThat(status).isEqualTo("DONE");
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("L'analyse " + runId + " ne s'est pas terminée dans le délai imparti");
    }
}
