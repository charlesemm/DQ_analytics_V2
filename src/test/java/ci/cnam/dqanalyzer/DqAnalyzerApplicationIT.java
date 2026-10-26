package ci.cnam.dqanalyzer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Démarre l'application complète sur un PostgreSQL jetable (Testcontainers) : vérifie que les migrations
 * Flyway passent, que le mapping JPA est valide et que la sécurité protège les pages.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DqAnalyzerApplicationIT {

    @Autowired
    MockMvc mvc;

    @Test
    void page_accueil_exige_une_connexion() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void page_de_connexion_accessible() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void point_de_sante_accessible() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void mauvais_identifiants_refuses() throws Exception {
        mvc.perform(formLogin().user("inconnu").password("faux"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }
}
