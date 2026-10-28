package ci.cnam.dqanalyzer.profiling.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Écran « Profil du fichier » (CDC §5.2) : ce que la page montre de chaque colonne (PRF-01 à PRF-03),
 * les suggestions de règles (PRF-04), le détail déplié par HTMX, et les autorisations (SEC-02).
 *
 * <p>Le fichier est enregistré comme l'aurait fait l'import — une ligne {@code source_file} au statut
 * {@code DONE} et une base DuckDB sur le disque — pour que ce soit bien {@code ImportService.tableOf}
 * qui soit exercé, et pas un bouchon.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfilePageControllerIT {

    private static final String TABLE = "ds_profil";

    @TempDir
    static Path directory;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long chargeId;
    private long enCoursId;

    @BeforeEach
    void prepareUnFichierCharge() throws Exception {
        Path database = directory.resolve("profil.duckdb");
        if (!Files.exists(database)) {
            Path csv = directory.resolve("assures.csv");
            Files.writeString(
                    csv,
                    """
                    matricule;sexe;ville
                    CI000001;M;Abidjan
                    CI000002;F;Bouaké
                    CI000003;M;
                    """);
            try (var connection = new DuckDbDatabases().open(database);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"" + TABLE + "\" AS SELECT row_number() OVER () + 1 AS \"_row_num\","
                        + " * FROM read_csv('"
                        + csv.toAbsolutePath().toString().replace('\\', '/')
                        + "', delim=';', header=true, all_varchar=true)");
            }
        }
        long userId = analysteId();
        chargeId = insererFichier(userId, "DONE", database.toString(), TABLE);
        enCoursId = insererFichier(userId, "RUNNING", null, null);
    }

    private long analysteId() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-profil', 'Analyste (profil)', 'analyste-profil@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST')
                ON CONFLICT (login) DO NOTHING
                """);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-profil'", Long.class);
    }

    private long insererFichier(long userId, String statut, String database, String table) {
        return jdbc.queryForObject(
                """
                INSERT INTO source_file (original_name, size_bytes, sha256, format, import_settings,
                                         storage_path, uploaded_by, import_status, import_progress,
                                         row_count, column_count, ragged_row_count, duckdb_path, table_name)
                VALUES ('assures.csv', 120, repeat('a', 64), 'DELIMITED', '{}'::jsonb,
                        '/tmp/assures.csv', ?, ?, 100, 3, 3, 0, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                statut,
                database,
                table);
    }

    @Test
    @WithMockUser(roles = "ANALYST")
    void montre_une_ligne_par_colonne_avec_son_type_et_son_remplissage() throws Exception {
        mvc.perform(get("/fichiers/{id}/profil", chargeId))
                .andExpect(status().isOk())
                .andExpect(view().name("profiling/profile"))
                .andExpect(content().string(containsString("matricule")))
                .andExpect(content().string(containsString("sexe")))
                .andExpect(content().string(containsString("ville")))
                .andExpect(content().string(containsString("Texte")))
                .andExpect(content().string(containsString("progress-bar")));
    }

    @Test
    @WithMockUser(roles = "ANALYST")
    void montre_les_regles_suggerees_et_un_lien_vers_le_formulaire_prerempli() throws Exception {
        mvc.perform(get("/fichiers/{id}/profil", chargeId))
                .andExpect(status().isOk())
                // Matricule : 3 valeurs toutes différentes et toujours renseignées, sur 8 caractères.
                .andExpect(content().string(containsString("UNI-01")))
                .andExpect(content().string(containsString("CMP-01")))
                .andExpect(content().string(containsString("VAL-03")))
                // Chaque suggestion ouvre le formulaire de règle prérempli (PRF-04 vers REG-01).
                .andExpect(content().string(containsString("/regles/nouvelle")))
                .andExpect(content().string(containsString("type=UNI-01")))
                .andExpect(content().string(containsString("column=matricule")));
    }

    @Test
    @WithMockUser(roles = "ANALYST")
    void deplie_les_valeurs_frequentes_et_les_motifs_d_une_colonne() throws Exception {
        mvc.perform(get("/fichiers/{id}/profil/colonne", chargeId).param("column", "matricule"))
                .andExpect(status().isOk())
                .andExpect(view().name("profiling/fragments/column-detail"))
                .andExpect(content().string(containsString("CI000001")))
                .andExpect(content().string(containsString("AA999999")));
    }

    @Test
    @WithMockUser(roles = "ANALYST")
    void refuse_une_colonne_qui_n_existe_pas_dans_le_fichier() throws Exception {
        mvc.perform(get("/fichiers/{id}/profil/colonne", chargeId).param("column", "inexistante"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(view().name("profiling/unavailable"))
                .andExpect(content().string(containsString("existe pas dans le fichier")));
    }

    @Test
    @WithMockUser(roles = "ANALYST")
    void dit_que_le_chargement_n_est_pas_termine_au_lieu_d_afficher_zero_ligne() throws Exception {
        mvc.perform(get("/fichiers/{id}/profil", enCoursId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(view().name("profiling/unavailable"))
                .andExpect(content().string(containsString("pas terminé")))
                .andExpect(content().string(not(containsString("progress-bar"))));
    }

    @Test
    @WithMockUser(roles = "ANALYST")
    void repond_introuvable_pour_un_fichier_inconnu() throws Exception {
        mvc.perform(get("/fichiers/{id}/profil", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(view().name("profiling/unavailable"));
    }

    @Test
    @WithMockUser(roles = "READER")
    void un_lecteur_consulte_le_profil_sans_pouvoir_rien_modifier() throws Exception {
        mvc.perform(get("/fichiers/{id}/profil", chargeId))
                .andExpect(status().isOk())
                // Un lecteur ne crée pas de règle : le lien de création ne lui est pas proposé.
                .andExpect(content().string(not(containsString("/regles/nouvelle"))))
                .andExpect(content().string(not(containsString("/imports/nouveau"))));
    }

    @Test
    void un_visiteur_non_authentifie_est_renvoye_a_la_connexion() throws Exception {
        mvc.perform(get("/fichiers/{id}/profil", chargeId)).andExpect(status().is3xxRedirection());
    }
}
