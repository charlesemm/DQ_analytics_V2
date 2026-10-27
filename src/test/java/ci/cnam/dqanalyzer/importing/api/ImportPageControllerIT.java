package ci.cnam.dqanalyzer.importing.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Écran d'import : qui y a accès (SEC-02), et ce que la page doit porter pour que le script
 * fonctionne — le jeton anti-rejeu et les listes de choix du CDC (IMP-03).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportPageControllerIT {

    @Autowired
    MockMvc mvc;

    @Test
    @WithMockUser(roles = "ANALYST")
    void l_analyste_ouvre_l_ecran_d_import() throws Exception {
        mvc.perform(get("/imports/nouveau"))
                .andExpect(status().isOk())
                .andExpect(view().name("importing/import"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("zone-depot")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("csrf-token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Point-virgule")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("windows-1252")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(".parquet")));
    }

    @Test
    @WithMockUser(roles = "READER")
    void un_lecteur_n_a_pas_acces_a_l_ecran_d_import() throws Exception {
        mvc.perform(get("/imports/nouveau")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ANALYST")
    void le_menu_propose_l_import_a_l_analyste() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/imports/nouveau")));
    }

    @Test
    @WithMockUser(roles = "READER")
    void le_menu_ne_propose_pas_l_import_a_un_lecteur() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/imports/nouveau"))));
    }
}
