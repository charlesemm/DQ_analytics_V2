package ci.cnam.dqanalyzer.rules.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.RuleCatalogService;
import ci.cnam.dqanalyzer.rules.RuleDefinitionReader;
import ci.cnam.dqanalyzer.rules.Severity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Export et import JSON des règles (REG-05) : l'export rend un fichier à télécharger, l'import
 * vérifie chaque règle, crée les nouvelles, versionne celles déjà connues et refuse les autres sans
 * bloquer le reste. L'import est interdit au Lecteur (SEC-02).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleExchangeControllerIT {

    private static final String OWNER = "Direction des prestations (échange)";

    @Autowired
    MockMvc mvc;

    @Autowired
    RuleCatalogService catalog;

    @Autowired
    RuleDefinitionReader reader;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void creerLesComptes() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-echange', 'Analyste (échange)', 'analyste-echange@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-echange', 'Lecteur (échange)', 'lecteur-echange@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-echange'", Long.class);
        jdbc.update("DELETE FROM dq_rule WHERE business_owner = ?", OWNER);
    }

    @Test
    @WithMockUser(username = "analyste-echange", roles = "ANALYST")
    void l_export_rend_un_fichier_json_telechargeable() throws Exception {
        catalog.create(
                new RuleCatalogService.RuleForm(
                        "Sexe dans la liste (échange)",
                        null,
                        "VAL-05",
                        List.of("sexe"),
                        Map.of("values", "M; F"),
                        new Normalization(true, false, false),
                        Severity.MAJOR,
                        99.0,
                        "La valeur du sexe doit être M ou F.",
                        OWNER,
                        "Création"),
                userId);

        mvc.perform(get("/regles/export").param("businessOwner", OWNER))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("regles-dq-")))
                .andExpect(content().string(containsString("Sexe dans la liste (échange)")))
                .andExpect(content().string(containsString("VAL-05")))
                .andExpect(content().string(containsString("\"trim\" : true")));
    }

    @Test
    @WithMockUser(username = "lecteur-echange", roles = "READER")
    void un_lecteur_peut_exporter_mais_pas_importer() throws Exception {
        mvc.perform(get("/regles/export")).andExpect(status().isOk());

        mvc.perform(multipart("/regles/import").file(fichier("[]")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "analyste-echange", roles = "ANALYST")
    void l_import_cree_les_regles_du_fichier() throws Exception {
        mvc.perform(multipart("/regles/import").file(fichier(unExport())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("rules/import-report"))
                .andExpect(content().string(containsString("Règles créées : 2")));

        var codes = jdbc.queryForList(
                "SELECT rule_code FROM dq_rule WHERE business_owner = ? AND is_current ORDER BY name",
                String.class,
                OWNER);
        assertThat(codes).hasSize(2);
        assertThat(reader.current(codes.getFirst()).version()).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "analyste-echange", roles = "ANALYST")
    void reimporter_le_meme_fichier_versionne_au_lieu_de_dupliquer() throws Exception {
        mvc.perform(multipart("/regles/import").file(fichier(unExport())).with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(multipart("/regles/import").file(fichier(unExport())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nouvelles versions : 2")));

        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT rule_code) FROM dq_rule WHERE business_owner = ?", Integer.class, OWNER))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT max(version) FROM dq_rule WHERE business_owner = ?", Integer.class, OWNER))
                .isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "analyste-echange", roles = "ANALYST")
    void une_regle_invalide_est_refusee_sans_empecher_les_autres() throws Exception {
        String content =
                """
                [
                  {"name": "Règle correcte (échange)", "type": "CMP-01", "targetColumns": ["num_secu"],
                   "severity": "BLOCKING", "thresholdPercent": 100, "businessOwner": "%s"},
                  {"name": "Règle sans condition (échange)", "type": "CMP-03",
                   "targetColumns": ["num_assure_principal"], "severity": "MAJOR",
                   "thresholdPercent": 100, "businessOwner": "%s"}
                ]
                """
                        .formatted(OWNER, OWNER);

        mvc.perform(multipart("/regles/import").file(fichier(content)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Règles créées : 1")))
                .andExpect(content().string(containsString("Règles refusées : 1")))
                .andExpect(content().string(containsString("Règle sans condition (échange)")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dq_rule WHERE business_owner = ?", Integer.class, OWNER))
                .isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "analyste-echange", roles = "ANALYST")
    void un_fichier_qui_n_est_pas_un_export_est_annonce_en_francais() throws Exception {
        mvc.perform(multipart("/regles/import")
                        .file(fichier("ceci n'est pas du JSON"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("export de règles lisible")));
    }

    private static MockMultipartFile fichier(String content) {
        return new MockMultipartFile(
                "file", "regles.json", "application/json", content.getBytes(StandardCharsets.UTF_8));
    }

    private static String unExport() {
        return """
                [
                  {"name": "Matricule unique (échange)", "type": "UNI-01", "targetColumns": ["matricule"],
                   "severity": "BLOCKING", "thresholdPercent": 100, "businessOwner": "%s"},
                  {"name": "Sexe autorisé (échange)", "type": "VAL-05", "targetColumns": ["sexe"],
                   "params": {"values": "M; F"}, "severity": "MAJOR", "thresholdPercent": 99,
                   "trim": true, "businessOwner": "%s"}
                ]
                """
                .formatted(OWNER, OWNER);
    }
}
