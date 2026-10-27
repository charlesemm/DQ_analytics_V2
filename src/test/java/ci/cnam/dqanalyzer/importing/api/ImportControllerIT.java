package ci.cnam.dqanalyzer.importing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.testsupport.Workbooks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Parcours complet d'un import, sur un PostgreSQL jetable : envoi du fichier, conservation du nom, de
 * la taille et de l'empreinte (IMP-06), chargement dans DuckDB, refus clairs (IMP-05), correction des
 * paramètres détectés (IMP-03) et autorisations (SEC-02).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportControllerIT {

    private static final String CSV = "id;nom;ville\n1;Koné Aminata;Abidjan\n2;Yao Désiré;Bouaké\n3;Traoré;Korhogo\n";

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
                VALUES ('analyste-it', 'Analyste (test)', 'analyste-it@dq.local', '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST'),
                       ('lecteur-it', 'Lecteur (test)', 'lecteur-it@dq.local', '$2a$10$abcdefghijklmnopqrstuv', 'READER')
                ON CONFLICT (login) DO NOTHING
                """);
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_analyste_importe_un_csv_et_la_table_est_chargee() throws Exception {
        var response = mvc.perform(
                        multipart("/api/imports").file(file("assures.csv", CSV)).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.settings.delimiter").value(";"))
                .andExpect(jsonPath("$.settings.charsetName").value("UTF-8"))
                .andExpect(jsonPath("$.hasHeader").value(true))
                .andExpect(jsonPath("$.originalName").value("assures.csv"))
                .andReturn();
        long id = json.readTree(response.getResponse().getContentAsString())
                .get("id")
                .asLong();
        awaitFinished(id);

        mvc.perform(get("/api/imports/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.raggedRowCount").value(0));

        var stored = jdbc.queryForMap(
                "SELECT original_name, size_bytes, sha256, format, row_count, column_count, table_name,"
                        + " storage_path, uploaded_by FROM source_file WHERE id = ?",
                id);
        assertThat(stored.get("original_name")).isEqualTo("assures.csv");
        assertThat((Long) stored.get("size_bytes")).isEqualTo(CSV.getBytes(StandardCharsets.UTF_8).length);
        assertThat((String) stored.get("sha256")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(stored.get("format")).isEqualTo("DELIMITED");
        assertThat(stored.get("row_count")).isEqualTo(3L);
        assertThat(stored.get("column_count")).isEqualTo(3);
        assertThat(stored.get("table_name")).isEqualTo("ds_" + id);
        assertThat((String) stored.get("storage_path")).doesNotContain("assures");
        assertThat(stored.get("uploaded_by")).isNotNull();
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_analyste_importe_un_classeur_excel_et_choisit_sa_feuille(@TempDir Path directory) throws Exception {
        Path classeur = Workbooks.write(
                directory.resolve("assures.xlsx"),
                Workbooks.sheet("Notice", List.of(List.of("mode d'emploi"))),
                Workbooks.sheet(
                        "Assurés",
                        List.of(
                                List.of("id", "nom", "naissance"),
                                List.of("1", "Koné Aminata", LocalDate.of(1994, 12, 31)))));

        var response = mvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile(
                                "file",
                                "assures.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                Files.readAllBytes(classeur)))
                        .param("sheet", "Assurés")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.format").value("XLSX"))
                .andExpect(jsonPath("$.settings.sheetName").value("Assurés"))
                .andExpect(jsonPath("$.settings.headerRow").value(1))
                .andReturn();
        long id = json.readTree(response.getResponse().getContentAsString())
                .get("id")
                .asLong();
        awaitFinished(id);

        mvc.perform(get("/api/imports/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.rowCount").value(1));
        var stored = jdbc.queryForMap("SELECT format, column_count FROM source_file WHERE id = ?", id);
        assertThat(stored.get("format")).isEqualTo("XLSX");
        assertThat(stored.get("column_count")).isEqualTo(3);
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_analyste_importe_un_fichier_parquet(@TempDir Path directory) throws Exception {
        Path parquet = parquet(directory);

        var response = mvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile(
                                "file", "assures.parquet", "application/octet-stream", Files.readAllBytes(parquet)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.format").value("PARQUET"))
                .andReturn();
        long id = json.readTree(response.getResponse().getContentAsString())
                .get("id")
                .asLong();
        awaitFinished(id);

        mvc.perform(get("/api/imports/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.rowCount").value(2));
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_analyste_importe_un_fichier_a_largeur_fixe_avec_son_gabarit() throws Exception {
        var response = mvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile(
                                "file",
                                "extraction.dat",
                                "text/plain",
                                "id nom       ville\n1  Koné      Abidjan\n".getBytes(StandardCharsets.UTF_8)))
                        .param("template", "id:1:3,nom:4:10,ville:14:8")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.format").value("FIXED_WIDTH"))
                .andReturn();
        long id = json.readTree(response.getResponse().getContentAsString())
                .get("id")
                .asLong();
        awaitFinished(id);

        mvc.perform(get("/api/imports/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.rowCount").value(1));
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_fichier_a_largeur_fixe_sans_gabarit_est_refuse_avec_un_message_clair() throws Exception {
        var response = mvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile(
                                "file", "extraction.dat", "text/plain", "1  Koné\n".getBytes(StandardCharsets.UTF_8)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn();
        long id = json.readTree(response.getResponse().getContentAsString())
                .get("id")
                .asLong();
        awaitFinished(id);

        mvc.perform(get("/api/imports/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("gabarit")));
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_analyste_importe_un_fichier_json() throws Exception {
        var response = mvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile(
                                "file",
                                "assures.json",
                                "application/json",
                                "[{\"id\":\"1\",\"adresse\":{\"ville\":\"Abidjan\"}}]"
                                        .getBytes(StandardCharsets.UTF_8)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.format").value("JSON"))
                .andReturn();
        long id = json.readTree(response.getResponse().getContentAsString())
                .get("id")
                .asLong();
        awaitFinished(id);

        mvc.perform(get("/api/imports/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.rowCount").value(1));
        assertThat(jdbc.queryForObject("SELECT column_count FROM source_file WHERE id = ?", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void le_separateur_choisi_par_l_analyste_prime_sur_la_detection() throws Exception {
        mvc.perform(multipart("/api/imports")
                        .file(file("mixte.csv", "a,b;c\n1,2;3\n"))
                        .param("delimiter", "COMMA")
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.settings.delimiter").value(","));
    }

    @Test
    @WithMockUser(username = "lecteur-it", roles = "READER")
    void un_lecteur_ne_peut_pas_importer() throws Exception {
        mvc.perform(multipart("/api/imports").file(file("assures.csv", CSV)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void un_visiteur_non_connecte_ne_peut_pas_importer() throws Exception {
        mvc.perform(multipart("/api/imports").file(file("assures.csv", CSV)).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_executable_renomme_en_csv_est_refuse_avec_un_message_clair() throws Exception {
        var executable = new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xff, (byte) 0xff};

        mvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("file", "assures.csv", "text/csv", executable))
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("ne correspond pas à son extension")));
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_fichier_vide_est_refuse() throws Exception {
        mvc.perform(multipart("/api/imports").file(file("vide.csv", "")).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("vide")));
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_format_non_pris_en_charge_est_refuse() throws Exception {
        mvc.perform(multipart("/api/imports").file(file("assures.pdf", CSV)).with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString(
                                "csv, dat, json, jsonl, ndjson, ods, parquet, tsv, txt, xls, xlsm, xlsx, xml")));
    }

    @Test
    @WithMockUser(username = "analyste-it", roles = "ANALYST")
    void un_import_inconnu_renvoie_une_erreur_claire() throws Exception {
        mvc.perform(get("/api/imports/{id}", 999_999)).andExpect(status().isNotFound());
    }

    /**
     * Le chargement se poursuit en tâche de fond après la réponse : on interroge l'avancement, comme
     * le fera l'écran. Au-delà du délai, c'est un vrai échec, pas une lenteur de machine.
     */
    private void awaitFinished(long id) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            String body = mvc.perform(get("/api/imports/{id}", id))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            if (body.contains("\"DONE\"") || body.contains("\"FAILED\"")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("L'import " + id + " ne s'est pas terminé dans le délai imparti");
    }

    /** Un fichier Parquet d'essai, écrit par DuckDB lui-même. */
    private static Path parquet(Path directory) throws SQLException {
        Path file = directory.resolve("assures.parquet");
        try (var connection = DriverManager.getConnection("jdbc:duckdb:");
                var statement = connection.createStatement()) {
            statement.execute("COPY (SELECT * FROM (VALUES ('1', 'Koné'), ('2', 'Yao')) AS t(id, nom)) TO '"
                    + file.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        return file;
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
