package ci.cnam.dqanalyzer.importing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Envoi par morceaux avec reprise (IMP-01, PERF-02) : un envoi ouvert, des morceaux poussés un par
 * un, la liste de ce qui est arrivé après une coupure, puis l'assemblage qui remet le fichier à
 * l'import. Un envoi appartient à son auteur (SEC-02).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadControllerIT {

    private static final String PREMIER_MORCEAU = "id;nom\n1;Koné Aminata\n";
    private static final String SECOND_MORCEAU = "2;Yao Désiré\n3;Traoré Fanta\n";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void creerLesComptes() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-up', 'Analyste (envoi)', 'analyste-up@dq.local', '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('autre-up', 'Autre analyste', 'autre-up@dq.local', '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-up', 'Lecteur (envoi)', 'lecteur-up@dq.local', '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
    }

    @Test
    @WithMockUser(username = "analyste-up", roles = "ANALYST")
    void un_fichier_arrive_en_deux_morceaux_puis_est_assemble() throws Exception {
        long uploadId = ouvrir("assures.csv", PREMIER_MORCEAU.length() + SECOND_MORCEAU.length());

        pousser(uploadId, 0, PREMIER_MORCEAU);
        mvc.perform(get("/api/uploads/{id}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedChunks").value(org.hamcrest.Matchers.contains(0)))
                .andExpect(jsonPath("$.receivedBytes").value(PREMIER_MORCEAU.getBytes(StandardCharsets.UTF_8).length));
        pousser(uploadId, 1, SECOND_MORCEAU);

        var response = mvc.perform(post("/api/uploads/{id}/complete", uploadId).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.originalName").value("assures.csv"))
                .andExpect(jsonPath("$.format").value("DELIMITED"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        long sourceFileId = json.readTree(response.getResponse().getContentAsString())
                .get("id")
                .asLong();

        // Le fichier assemblé suit le chemin habituel : aperçu, puis chargement.
        mvc.perform(get("/api/imports/{id}/preview", sourceFileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[2].values[1]").value("Traoré Fanta"));
        assertThat(jdbc.queryForObject(
                        "SELECT completed_at IS NOT NULL FROM upload_session WHERE id = ?", Boolean.class, uploadId))
                .isTrue();
    }

    @Test
    @WithMockUser(username = "analyste-up", roles = "ANALYST")
    void un_morceau_renvoye_apres_une_coupure_ne_compte_pas_deux_fois() throws Exception {
        long uploadId = ouvrir("assures.csv", 0);

        pousser(uploadId, 0, PREMIER_MORCEAU);
        pousser(uploadId, 0, PREMIER_MORCEAU); // la connexion avait lâché : le navigateur le renvoie

        mvc.perform(get("/api/uploads/{id}", uploadId))
                .andExpect(jsonPath("$.receivedChunks").value(org.hamcrest.Matchers.contains(0)))
                .andExpect(jsonPath("$.receivedBytes").value(PREMIER_MORCEAU.getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    @WithMockUser(username = "analyste-up", roles = "ANALYST")
    void un_envoi_auquel_il_manque_un_morceau_n_est_pas_assemble() throws Exception {
        long uploadId = ouvrir("assures.csv", 0);
        pousser(uploadId, 0, PREMIER_MORCEAU);
        pousser(uploadId, 2, SECOND_MORCEAU); // le morceau 1 n'est jamais arrivé

        mvc.perform(post("/api/uploads/{id}/complete", uploadId).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("morceau 1")));
    }

    @Test
    @WithMockUser(username = "analyste-up", roles = "ANALYST")
    void un_format_refuse_l_est_avant_le_transfert() throws Exception {
        mvc.perform(post("/api/uploads").param("fileName", "assures.pdf").with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("format accepté")));
    }

    @Test
    @WithMockUser(username = "autre-up", roles = "ANALYST")
    void l_envoi_d_un_autre_analyste_est_introuvable() throws Exception {
        long uploadId = ouvrirPour("analyste-up", "assures.csv");

        mvc.perform(get("/api/uploads/{id}", uploadId)).andExpect(status().isNotFound());
        mvc.perform(put("/api/uploads/{id}/chunks/{n}", uploadId, 0)
                        .content(PREMIER_MORCEAU.getBytes(StandardCharsets.UTF_8))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "lecteur-up", roles = "READER")
    void un_lecteur_ne_peut_pas_ouvrir_un_envoi() throws Exception {
        mvc.perform(post("/api/uploads").param("fileName", "assures.csv").with(csrf()))
                .andExpect(status().isForbidden());
    }

    private long ouvrir(String fileName, long totalSize) throws Exception {
        var response = mvc.perform(post("/api/uploads")
                        .param("fileName", fileName)
                        .param("totalSize", String.valueOf(totalSize))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunkSize").isNumber())
                .andReturn();
        return json.readTree(response.getResponse().getContentAsString())
                .get("uploadId")
                .asLong();
    }

    /** Ouvre un envoi au nom d'un autre analyste, pour vérifier qu'on ne peut pas y toucher. */
    @WithMockUser(username = "analyste-up", roles = "ANALYST")
    private long ouvrirPour(String login, String fileName) {
        jdbc.update(
                """
                INSERT INTO upload_session (file_name, chunk_size, created_by)
                VALUES (?, 1048576, (SELECT id FROM app_user WHERE login = ?))
                """,
                fileName,
                login);
        return jdbc.queryForObject("SELECT max(id) FROM upload_session", Long.class);
    }

    private void pousser(long uploadId, int chunkNumber, String content) throws Exception {
        mvc.perform(put("/api/uploads/{id}/chunks/{n}", uploadId, chunkNumber)
                        .content(content.getBytes(StandardCharsets.UTF_8))
                        .with(csrf()))
                .andExpect(status().isOk());
    }
}
