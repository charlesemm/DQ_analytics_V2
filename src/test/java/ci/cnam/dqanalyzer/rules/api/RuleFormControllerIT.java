package ci.cnam.dqanalyzer.rules.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.rules.RuleDefinitionReader;
import java.util.List;
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
 * Formulaire de règle (REG-01, REG-02) : champs générés depuis le paramSchema(), vérification des
 * paramètres avant enregistrement, modification qui crée une version, et droits (SEC-02) — un
 * Lecteur ne peut ni créer ni modifier une règle.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleFormControllerIT {

    private static final String OWNER = "Direction des prestations (formulaire)";

    @Autowired
    MockMvc mvc;

    @Autowired
    RuleDefinitionReader reader;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void creerLesComptes() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-formulaire', 'Analyste du formulaire', 'analyste-formulaire@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-formulaire', 'Lecteur du formulaire', 'lecteur-formulaire@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
        jdbc.update("DELETE FROM dq_rule WHERE business_owner = ?", OWNER);
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void les_champs_du_formulaire_viennent_du_schema_du_type_choisi() throws Exception {
        mvc.perform(get("/regles/nouvelle").param("type", "VAL-04"))
                .andExpect(status().isOk())
                .andExpect(view().name("rules/form"))
                // VAL-04 déclare les paramètres min et max : leurs libellés français apparaissent.
                .andExpect(content().string(containsString("params[min]")))
                .andExpect(content().string(containsString("params[max]")))
                .andExpect(content().string(containsString("Valeur minimale")))
                .andExpect(content().string(containsString("Validité")));
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void le_fragment_htmx_recharge_les_parametres_quand_le_type_change() throws Exception {
        mvc.perform(get("/regles/formulaire/parametres").param("type", "VAL-05"))
                .andExpect(status().isOk())
                .andExpect(view().name("rules/fragments/params"))
                .andExpect(content().string(containsString("params[values]")));
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void une_regle_saisie_est_enregistree_en_version_1() throws Exception {
        mvc.perform(post("/regles")
                        .param("name", "Sexe dans la liste")
                        .param("type", "VAL-05")
                        .param("targetColumns", "sexe")
                        .param("params[values]", "M; F")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "99")
                        .param("businessOwner", OWNER)
                        .param("changeComment", "Création")
                        .param("trim", "true")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/regles"));

        var rule = reader.current(code());
        assertThat(rule.version()).isEqualTo(1);
        assertThat(rule.type()).isEqualTo("VAL-05");
        assertThat(rule.targetColumns()).containsExactly("sexe");
        assertThat(rule.params()).containsEntry("values", "M; F");
        assertThat(rule.normalization().trim()).isTrue();
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void une_condition_construite_par_l_editeur_est_acceptee() throws Exception {
        mvc.perform(post("/regles")
                        .param("name", "N° d'assuré principal obligatoire pour un ayant droit")
                        .param("type", "CMP-03")
                        .param("targetColumns", "num_assure_principal")
                        .param(
                                "params[when]",
                                "{\"all\":[{\"column\":\"statut\",\"op\":\"eq\",\"value\":\"Ayant droit\","
                                        + "\"as\":\"text\"}]}")
                        .param("severity", "BLOCKING")
                        .param("thresholdPercent", "100")
                        .param("businessOwner", OWNER)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(reader.current(code()).params()).containsKey("when");
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void un_parametre_manquant_est_refuse_avant_enregistrement_avec_un_message_francais() throws Exception {
        mvc.perform(post("/regles")
                        .param("name", "Obligatoire sans condition")
                        .param("type", "CMP-03")
                        .param("targetColumns", "num_assure_principal")
                        .param("severity", "MINOR")
                        .param("thresholdPercent", "100")
                        .param("businessOwner", OWNER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("rules/form"))
                .andExpect(content().string(containsString("Obligatoire sans condition")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dq_rule WHERE business_owner = ?", Integer.class, OWNER))
                .isZero();
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void une_modification_sans_motif_est_refusee() throws Exception {
        String code = creerUneRegleSimple();

        mvc.perform(post("/regles/{code}", code)
                        .param("name", "Libellé corrigé")
                        .param("type", "VAL-05")
                        .param("targetColumns", "sexe")
                        .param("params[values]", "M; F")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "99")
                        .param("businessOwner", OWNER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("rules/form"));

        assertThat(reader.versions(code)).hasSize(1);
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void une_modification_motivee_cree_la_version_2() throws Exception {
        String code = creerUneRegleSimple();

        mvc.perform(post("/regles/{code}", code)
                        .param("name", "Libellé corrigé")
                        .param("type", "VAL-05")
                        .param("targetColumns", "sexe")
                        .param("params[values]", "M; F; I")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "99")
                        .param("businessOwner", OWNER)
                        .param("changeComment", "Ajout de la valeur I")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(reader.versions(code)).hasSize(2);
        assertThat(reader.current(code).name()).isEqualTo("Libellé corrigé");
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void le_formulaire_de_modification_est_prerempli_avec_la_version_courante() throws Exception {
        String code = creerUneRegleSimple();

        mvc.perform(get("/regles/{code}/modifier", code))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sexe dans la liste")))
                .andExpect(content().string(containsString("M; F")))
                .andExpect(content().string(containsString(OWNER)));
    }

    @Test
    @WithMockUser(username = "analyste-formulaire", roles = "ANALYST")
    void dupliquer_propose_la_meme_saisie_sous_un_nouveau_nom() throws Exception {
        String code = creerUneRegleSimple();

        mvc.perform(get("/regles/{code}/dupliquer", code))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sexe dans la liste (copie)")));
    }

    @Test
    @WithMockUser(username = "lecteur-formulaire", roles = "READER")
    void un_lecteur_ne_peut_pas_ouvrir_le_formulaire() throws Exception {
        mvc.perform(get("/regles/nouvelle")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "lecteur-formulaire", roles = "READER")
    void un_lecteur_ne_peut_pas_creer_une_regle() throws Exception {
        mvc.perform(post("/regles")
                        .param("name", "Règle interdite")
                        .param("type", "CMP-01")
                        .param("targetColumns", "num_secu")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "100")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "lecteur-formulaire", roles = "READER")
    void un_lecteur_ne_peut_pas_modifier_une_regle() throws Exception {
        mvc.perform(post("/regles/{code}", "R-0001")
                        .param("name", "Règle interdite")
                        .param("type", "CMP-01")
                        .param("targetColumns", "num_secu")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "100")
                        .param("changeComment", "Interdit")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private String creerUneRegleSimple() throws Exception {
        mvc.perform(post("/regles")
                        .param("name", "Sexe dans la liste")
                        .param("type", "VAL-05")
                        .param("targetColumns", "sexe")
                        .param("params[values]", "M; F")
                        .param("severity", "MAJOR")
                        .param("thresholdPercent", "99")
                        .param("businessOwner", OWNER)
                        .param("changeComment", "Création")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        return code();
    }

    /** Code de la règle créée par le test en cours : une seule règle porte ce propriétaire. */
    private String code() {
        List<String> codes = jdbc.queryForList(
                "SELECT DISTINCT rule_code FROM dq_rule WHERE business_owner = ?", String.class, OWNER);
        assertThat(codes).hasSize(1);
        return codes.getFirst();
    }
}
