package ci.cnam.dqanalyzer.templates.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * Écrans des modèles d'analyse (MOD-01 à MOD-03) : composition d'un modèle, association proposée des
 * colonnes avec un fichier réel, correction mémorisée, et droits (SEC-02).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateControllerIT {

    private static final String TABLE = "ds_modele";
    private static final String OWNER = "Direction des prestations (modèles)";

    @TempDir
    static Path directory;

    @Autowired
    MockMvc mvc;

    @Autowired
    TemplateService templates;

    @Autowired
    RuleCatalogService rules;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;
    private long fileId;
    private String ruleCode;

    @BeforeEach
    void prepare() throws Exception {
        Path database = directory.resolve("modele.duckdb");
        if (!Files.exists(database)) {
            Path csv = directory.resolve("assures-modele.csv");
            Files.writeString(
                    csv,
                    """
                    MATRICULE;DATE_DE_NAISSANCE;ville
                    CI000001;01/01/1980;Abidjan
                    CI000002;02/02/1990;Bouaké
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
                VALUES ('analyste-modele', 'Analyste (modèles)', 'analyste-modele@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-modele', 'Lecteur (modèles)', 'lecteur-modele@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-modele'", Long.class);
        fileId = jdbc.queryForObject(
                """
                INSERT INTO source_file (original_name, size_bytes, sha256, format, import_settings,
                                         storage_path, uploaded_by, import_status, import_progress,
                                         row_count, column_count, ragged_row_count, duckdb_path, table_name)
                VALUES ('assures-modele.csv', 120, repeat('c', 64), 'DELIMITED', '{}'::jsonb,
                        '/tmp/assures-modele.csv', ?, 'DONE', 100, 2, 3, 0, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                database.toString(),
                TABLE);
        jdbc.update("DELETE FROM analysis_template WHERE name LIKE 'Modèle de test%'");
        jdbc.update("DELETE FROM dq_rule WHERE business_owner = ?", OWNER);
        ruleCode = rules.create(
                new RuleCatalogService.RuleForm(
                        "Matricule obligatoire (modèles)",
                        null,
                        "CMP-01",
                        List.of("Matricule"),
                        Map.of(),
                        Normalization.NONE,
                        Severity.BLOCKING,
                        100.0,
                        null,
                        OWNER,
                        "Création"),
                userId);
    }

    @Test
    @WithMockUser(username = "analyste-modele", roles = "ANALYST")
    void un_modele_est_compose_de_colonnes_attendues_de_poids_et_de_regles() throws Exception {
        mvc.perform(post("/modeles")
                        .param("name", "Modèle de test — assurés")
                        .param("description", "Contrôles de base du fichier des assurés")
                        .param("expectedColumns", "Matricule\nDate de naissance\n\n  Ville  ")
                        .param("weights[COMPLETENESS]", "3")
                        .param("weights[VALIDITY]", "2")
                        .param("ruleCodes", ruleCode)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        long id = templateId();
        var template = templates.byId(id);
        assertThat(template.expectedColumns()).containsExactly("Matricule", "Date de naissance", "Ville");
        assertThat(template.ruleCodes()).containsExactly(ruleCode);
        assertThat(template.weightOf(Dimension.COMPLETENESS)).isEqualTo(3);
        assertThat(template.weightOf(Dimension.TIMELINESS)).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "analyste-modele", roles = "ANALYST")
    void un_modele_sans_nom_est_refuse_avec_un_message_francais() throws Exception {
        mvc.perform(post("/modeles")
                        .param("name", "  ")
                        .param("expectedColumns", "Matricule")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("templates/form"))
                .andExpect(content().string(containsString("Le nom du modèle est obligatoire")));
    }

    @Test
    @WithMockUser(username = "analyste-modele", roles = "ANALYST")
    void l_association_est_proposee_malgre_les_accents_et_la_casse() throws Exception {
        long id = unModele();

        mvc.perform(get("/modeles/{id}/colonnes", id).param("sourceFileId", String.valueOf(fileId)))
                .andExpect(status().isOk())
                .andExpect(view().name("templates/columns"))
                // « Date de naissance » reconnue dans « DATE_DE_NAISSANCE », « Commune » introuvable.
                .andExpect(content().string(containsString("DATE_DE_NAISSANCE")))
                .andExpect(content().string(containsString("À choisir")));
    }

    @Test
    @WithMockUser(username = "analyste-modele", roles = "ANALYST")
    void la_correction_de_l_analyste_est_memorisee_et_reproposee() throws Exception {
        long id = unModele();

        mvc.perform(post("/modeles/{id}/colonnes", id)
                        .param("colonne.Matricule", "MATRICULE")
                        .param("colonne.Date de naissance", "DATE_DE_NAISSANCE")
                        .param("colonne.Commune", "ville")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(templates.byId(id).columnMapping())
                .containsEntry("Matricule", "MATRICULE")
                .containsEntry("Commune", "ville");
        assertThat(templates.proposeMapping(id, List.of("MATRICULE", "DATE_DE_NAISSANCE", "ville")))
                .containsEntry("Commune", "ville");
    }

    @Test
    @WithMockUser(username = "analyste-modele", roles = "ANALYST")
    void une_colonne_laissee_vide_n_est_pas_enregistree() throws Exception {
        long id = unModele();

        mvc.perform(post("/modeles/{id}/colonnes", id)
                        .param("colonne.Matricule", "MATRICULE")
                        .param("colonne.Commune", "")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(templates.byId(id).columnMapping()).containsOnlyKeys("Matricule");
    }

    @Test
    @WithMockUser(username = "lecteur-modele", roles = "READER")
    void un_lecteur_consulte_les_modeles_sans_pouvoir_les_composer() throws Exception {
        unModele();

        mvc.perform(get("/modeles")).andExpect(status().isOk());
        mvc.perform(get("/modeles/nouveau")).andExpect(status().isForbidden());
        mvc.perform(post("/modeles").param("name", "Interdit").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "lecteur-modele", roles = "READER")
    void un_lecteur_ne_peut_pas_enregistrer_une_association() throws Exception {
        long id = unModele();

        mvc.perform(post("/modeles/{id}/colonnes", id)
                        .param("colonne.Matricule", "MATRICULE")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "analyste-modele", roles = "ANALYST")
    void un_modele_inconnu_donne_une_page_404_en_francais() throws Exception {
        mvc.perform(get("/modeles/{id}/colonnes", 999999))
                .andExpect(status().isNotFound())
                .andExpect(view().name("templates/unavailable"));
    }

    private long unModele() {
        templates.create(
                new TemplateService.TemplateForm(
                        "Modèle de test — assurés",
                        "Contrôles de base",
                        List.of("Matricule", "Date de naissance", "Commune"),
                        Map.of(Dimension.COMPLETENESS, 3),
                        List.of(ruleCode)),
                userId);
        return templateId();
    }

    private long templateId() {
        return jdbc.queryForObject(
                "SELECT id FROM analysis_template WHERE name LIKE 'Modèle de test%' ORDER BY id DESC LIMIT 1",
                Long.class);
    }
}
