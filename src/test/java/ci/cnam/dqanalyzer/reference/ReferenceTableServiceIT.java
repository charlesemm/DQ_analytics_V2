package ci.cnam.dqanalyzer.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Chargement des tables de référence (REF-01, REF-02) : charger deux fois le même nom crée les
 * versions 1 puis 2, et la version 1 reste intacte — des analyses passées la citent (TRA-02).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceTableServiceIT {

    private static final String NAME = "Communes (test service)";

    private static final String V1 =
            """
            code;nom
            001;PLATEAU
            002;COCODY
            003;YOPOUGON
            """;

    /** La version 2 retire COCODY et ajoute MARCORY : de quoi prouver que la v1 n'a pas bougé. */
    private static final String V2 =
            """
            code;nom
            001;PLATEAU
            003;YOPOUGON
            004;MARCORY
            """;

    @Autowired
    ReferenceTableService references;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void creerLeCompte() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-reference', 'Analyste (références)', 'analyste-reference@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST')
                ON CONFLICT (login) DO NOTHING
                """);
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-reference'", Long.class);
        jdbc.update(
                "DELETE FROM reference_value WHERE reference_table_id IN (SELECT id FROM reference_table WHERE name = ?)",
                NAME);
        jdbc.update("DELETE FROM reference_table WHERE name = ?", NAME);
    }

    @Test
    void un_premier_chargement_cree_la_version_1_avec_ses_lignes() {
        var loaded = references.load(NAME, csv(V1), "communes.csv", userId);

        assertThat(loaded.version()).isEqualTo(1);
        assertThat(loaded.rowCount()).isEqualTo(3);
        assertThat(loaded.columns()).containsExactly("code", "nom");
        assertThat(loaded.loadedBy()).isEqualTo("Analyste (références)");
        assertThat(references.preview(loaded.id()))
                .containsExactly(List.of("001", "PLATEAU"), List.of("002", "COCODY"), List.of("003", "YOPOUGON"));
    }

    @Test
    void un_second_chargement_cree_la_version_2_et_laisse_la_version_1_intacte() {
        var premiere = references.load(NAME, csv(V1), "communes.csv", userId);

        var seconde = references.load(NAME, csv(V2), "communes.csv", userId);

        assertThat(seconde.version()).isEqualTo(2);
        assertThat(seconde.id()).isNotEqualTo(premiere.id());
        // La v1 garde ses trois lignes d'origine, COCODY comprise.
        assertThat(references.byId(premiere.id()).rowCount()).isEqualTo(3);
        assertThat(references.preview(premiere.id())).contains(List.of("002", "COCODY"));
        assertThat(references.preview(seconde.id())).doesNotContain(List.of("002", "COCODY"));
        assertThat(references.versions(NAME))
                .extracting(ReferenceTable::version)
                .containsExactly(2, 1);
    }

    @Test
    void la_version_courante_est_la_plus_recente() {
        references.load(NAME, csv(V1), "communes.csv", userId);
        references.load(NAME, csv(V2), "communes.csv", userId);

        assertThat(references.currentVersions())
                .filteredOn(reference -> reference.name().equals(NAME))
                .singleElement()
                .extracting(ReferenceTable::version)
                .isEqualTo(2);
    }

    @Test
    void un_referentiel_sans_nom_est_refuse() {
        assertThatThrownBy(() -> references.load("  ", csv(V1), "communes.csv", userId))
                .isInstanceOf(ReferenceRejectedException.class)
                .hasMessage("reference.error.nom-obligatoire");
    }

    @Test
    void un_fichier_vide_est_refuse_comme_a_l_import() {
        assertThatThrownBy(() -> references.load(NAME, csv(""), "communes.csv", userId))
                .isInstanceOf(ImportRejectedException.class);

        assertThat(references.versions(NAME)).isEmpty();
    }

    private static ByteArrayInputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
