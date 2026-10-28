package ci.cnam.dqanalyzer.reference.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.reference.ReferenceTableService;
import java.nio.charset.StandardCharsets;
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
 * Écran des tables de référence (REF-01, REF-02) : chargement d'une version, aperçu, historique, et
 * droits (SEC-02) — un Lecteur consulte, il ne charge pas.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceControllerIT {

    private static final String NAME = "Communes (test écran)";

    @Autowired
    MockMvc mvc;

    @Autowired
    ReferenceTableService references;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void creerLesComptes() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-ref-ecran', 'Analyste (écran références)', 'analyste-ref-ecran@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-ref-ecran', 'Lecteur (écran références)', 'lecteur-ref-ecran@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
        jdbc.update(
                "DELETE FROM reference_value WHERE reference_table_id IN (SELECT id FROM reference_table WHERE name = ?)",
                NAME);
        jdbc.update("DELETE FROM reference_table WHERE name = ?", NAME);
    }

    @Test
    @WithMockUser(username = "analyste-ref-ecran", roles = "ANALYST")
    void un_analyste_charge_une_version_et_arrive_sur_son_apercu() throws Exception {
        mvc.perform(multipart("/references")
                        .file(fichier("code;nom\n001;PLATEAU\n002;COCODY\n"))
                        .param("name", NAME)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        long id = references.currentVersions().stream()
                .filter(reference -> reference.name().equals(NAME))
                .findFirst()
                .orElseThrow()
                .id();
        mvc.perform(get("/references/versions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(view().name("reference/preview"))
                .andExpect(content().string(containsString("PLATEAU")))
                .andExpect(content().string(containsString("COCODY")));
    }

    @Test
    @WithMockUser(username = "analyste-ref-ecran", roles = "ANALYST")
    void la_liste_et_l_historique_montrent_les_deux_versions() throws Exception {
        charger("code;nom\n001;PLATEAU\n");
        charger("code;nom\n001;PLATEAU\n002;COCODY\n");

        mvc.perform(get("/references"))
                .andExpect(status().isOk())
                .andExpect(view().name("reference/list"))
                .andExpect(content().string(containsString(NAME)))
                .andExpect(content().string(containsString("v2")));

        mvc.perform(get("/references/{name}/versions", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("reference/versions"))
                .andExpect(content().string(containsString("Courante")));
    }

    @Test
    @WithMockUser(username = "analyste-ref-ecran", roles = "ANALYST")
    void un_fichier_sans_nom_de_referentiel_est_refuse_en_francais() throws Exception {
        mvc.perform(multipart("/references")
                        .file(fichier("code;nom\n001;PLATEAU\n"))
                        .param("name", "   ")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(view().name("reference/unavailable"))
                .andExpect(content().string(containsString("Donnez un nom au référentiel")));

        assertThat(references.versions(NAME)).isEmpty();
    }

    @Test
    @WithMockUser(username = "analyste-ref-ecran", roles = "ANALYST")
    void un_referentiel_inconnu_donne_une_page_404() throws Exception {
        mvc.perform(get("/references/versions/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(view().name("reference/unavailable"));
    }

    @Test
    @WithMockUser(username = "lecteur-ref-ecran", roles = "READER")
    void un_lecteur_consulte_mais_ne_charge_pas() throws Exception {
        mvc.perform(get("/references")).andExpect(status().isOk());

        mvc.perform(multipart("/references")
                        .file(fichier("code;nom\n001;PLATEAU\n"))
                        .param("name", NAME)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    private void charger(String content) throws Exception {
        mvc.perform(multipart("/references")
                        .file(fichier(content))
                        .param("name", NAME)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private static MockMultipartFile fichier(String content) {
        return new MockMultipartFile("file", "communes.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
