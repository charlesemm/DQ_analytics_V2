package ci.cnam.dqanalyzer.rules.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.RuleCatalogService;
import ci.cnam.dqanalyzer.rules.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Écran « Règles de qualité » (REG-06) : la liste et ses filtres, l'historique des versions (REG-04)
 * et les droits (SEC-02) — un Lecteur consulte, il ne peut ni activer ni désactiver une règle.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleListControllerIT {

    private static final String OWNER = "Direction des prestations (liste)";

    @Autowired
    MockMvc mvc;

    @Autowired
    RuleCatalogService catalog;

    @Autowired
    JdbcTemplate jdbc;

    private String completenessCode;
    private String validityCode;

    @BeforeEach
    void creerLesComptesEtDeuxRegles() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-liste', 'Analyste des règles', 'analyste-liste@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-liste', 'Lecteur des règles', 'lecteur-liste@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
        long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-liste'", Long.class);
        jdbc.update("DELETE FROM dq_rule WHERE business_owner = ?", OWNER);
        completenessCode =
                catalog.create(form("Numéro de sécurité obligatoire", "CMP-01", List.of("num_secu"), Map.of()), userId);
        validityCode = catalog.create(
                form("Sexe dans la liste", "VAL-05", List.of("sexe"), Map.of("allowedValues", List.of("M", "F"))),
                userId);
    }

    @Test
    @WithMockUser(username = "analyste-liste", roles = "ANALYST")
    void la_liste_affiche_les_regles_avec_leur_version_et_leur_proprietaire() throws Exception {
        mvc.perform(get("/regles"))
                .andExpect(status().isOk())
                .andExpect(view().name("rules/list"))
                .andExpect(content().string(containsString(completenessCode)))
                .andExpect(content().string(containsString("Numéro de sécurité obligatoire")))
                .andExpect(content().string(containsString(OWNER)))
                .andExpect(content().string(containsString("Champ obligatoire non vide")));
    }

    @Test
    @WithMockUser(username = "analyste-liste", roles = "ANALYST")
    void le_filtre_par_dimension_ne_garde_que_les_regles_de_cette_dimension() throws Exception {
        mvc.perform(get("/regles").param("dimension", "VALIDITY").param("businessOwner", OWNER))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(validityCode)))
                .andExpect(content().string(not(containsString(completenessCode))));
    }

    @Test
    @WithMockUser(username = "analyste-liste", roles = "ANALYST")
    void un_analyste_desactive_une_regle_sans_creer_de_version() throws Exception {
        mvc.perform(post("/regles/{code}/activation", completenessCode)
                        .param("active", "false")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(get("/regles").param("businessOwner", OWNER).param("active", "false"))
                .andExpect(content().string(containsString(completenessCode)));
    }

    @Test
    @WithMockUser(username = "analyste-liste", roles = "ANALYST")
    void l_historique_montre_les_deux_versions_apres_une_modification() throws Exception {
        catalog.update(
                completenessCode,
                form("Libellé corrigé", "CMP-01", List.of("num_secu"), Map.of()),
                jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-liste'", Long.class));

        mvc.perform(get("/regles/{code}/historique", completenessCode))
                .andExpect(status().isOk())
                .andExpect(view().name("rules/history"))
                .andExpect(model().attribute("ruleCode", completenessCode))
                .andExpect(content().string(containsString("Libellé corrigé")))
                .andExpect(content().string(containsString("Numéro de sécurité obligatoire")));
    }

    @Test
    @WithMockUser(username = "analyste-liste", roles = "ANALYST")
    void une_regle_inconnue_donne_une_page_404_en_francais() throws Exception {
        mvc.perform(get("/regles/{code}/historique", "R-9999"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("rules/unavailable"));
    }

    @Test
    @WithMockUser(username = "lecteur-liste", roles = "READER")
    void un_lecteur_consulte_la_liste() throws Exception {
        mvc.perform(get("/regles")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "lecteur-liste", roles = "READER")
    void un_lecteur_ne_peut_pas_activer_ni_desactiver_une_regle() throws Exception {
        mvc.perform(post("/regles/{code}/activation", completenessCode)
                        .param("active", "false")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private RuleCatalogService.RuleForm form(
            String name, String type, List<String> columns, Map<String, Object> params) {
        return new RuleCatalogService.RuleForm(
                name,
                "Règle de test de la liste",
                type,
                columns,
                params,
                Normalization.NONE,
                Severity.MAJOR,
                95.0,
                "Message destiné à l'analyste.",
                OWNER,
                "Création");
    }
}
