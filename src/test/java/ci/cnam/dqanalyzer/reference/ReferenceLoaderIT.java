package ci.cnam.dqanalyzer.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluatorRegistry;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import ci.cnam.dqanalyzer.testsupport.Rules;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Copie d'une référence dans la base DuckDB d'une analyse, puis exécution réelle de INT-01 dessus
 * (REF-03) : un code absent de la version pointée est détecté, et la version compte.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferenceLoaderIT {

    private static final String NAME = "Communes (test loader)";
    private static final String TABLE = "ds_assures";

    @TempDir
    static Path directory;

    @Autowired
    ReferenceTableService references;

    @Autowired
    ReferenceLoader loader;

    @Autowired
    RuleEvaluatorRegistry registry;

    @Autowired
    DuckDbDatabases databases;

    @Autowired
    JdbcTemplate jdbc;

    private long v1;
    private long v2;

    @BeforeEach
    void chargerDeuxVersions() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-loader', 'Analyste (loader)', 'analyste-loader@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST')
                ON CONFLICT (login) DO NOTHING
                """);
        long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-loader'", Long.class);
        jdbc.update(
                "DELETE FROM reference_value WHERE reference_table_id IN (SELECT id FROM reference_table WHERE name = ?)",
                NAME);
        jdbc.update("DELETE FROM reference_table WHERE name = ?", NAME);
        // v1 contient COCODY ; v2 ne l'a plus : une commune supprimée du référentiel.
        v1 = references
                .load(NAME, csv("code;nom\n001;PLATEAU\n002;COCODY\n"), "communes.csv", userId)
                .id();
        v2 = references
                .load(NAME, csv("code;nom\n001;PLATEAU\n003;YOPOUGON\n"), "communes.csv", userId)
                .id();
    }

    @Test
    void int01_sur_la_version_2_detecte_la_commune_supprimee() throws Exception {
        var anomalies = new ArrayList<Anomaly>();
        try (Connection duck = databases.open(directory.resolve("analyse-v2.duckdb"))) {
            var data = assures(duck);
            var provider = loader.loadInto(duck, Set.of(v2));
            var rule = Rules.rule(
                    "INT-01",
                    Dimension.REFERENTIAL_INTEGRITY,
                    List.of("commune"),
                    Map.of("referenceTableId", v2, "referenceColumn", "code"));

            var result = registry.get("INT-01").evaluate(data, rule, provider, anomalies::add);

            assertThat(result.checkedRows()).isEqualTo(3);
            assertThat(result.conformingRows()).isEqualTo(2);
            // Ligne 3 du fichier : le code 002, retiré de la version 2.
            assertThat(anomalies).singleElement().satisfies(anomaly -> {
                assertThat(anomaly.rowNum()).isEqualTo(3);
                assertThat(anomaly.value()).isEqualTo("002");
            });
        }
    }

    @Test
    void la_meme_regle_sur_la_version_1_ne_trouve_rien_a_redire_du_code_002() throws Exception {
        try (Connection duck = databases.open(directory.resolve("analyse-v1.duckdb"))) {
            var data = assures(duck);
            var provider = loader.loadInto(duck, Set.of(v1));
            var rule = Rules.rule(
                    "INT-01",
                    Dimension.REFERENTIAL_INTEGRITY,
                    List.of("commune"),
                    Map.of("referenceTableId", v1, "referenceColumn", "code"));

            var result = registry.get("INT-01").evaluate(data, rule, provider, anomaly -> {});

            // 003 est absent de la v1 : c'est lui qui devient l'anomalie, à la place de 002.
            assertThat(result.checkedRows()).isEqualTo(3);
            assertThat(result.conformingRows()).isEqualTo(2);
        }
    }

    @Test
    void une_version_non_chargee_donne_un_message_clair() throws Exception {
        try (Connection duck = databases.open(directory.resolve("analyse-absente.duckdb"))) {
            var provider = loader.loadInto(duck, Set.of(v2));

            assertThatThrownBy(() -> provider.duckDbTable(v1))
                    .isInstanceOf(RuleEvaluationException.class)
                    .hasMessageContaining("n'a pas été chargée pour cette analyse");
        }
    }

    /** Fichier analysé : trois communes, dont 002 (retirée en v2) et 003 (absente en v1). */
    private Dataset assures(Connection duck) throws Exception {
        try (var statement = duck.createStatement()) {
            statement.execute("CREATE OR REPLACE TABLE \"" + TABLE + "\" (\"_row_num\" BIGINT, \"commune\" VARCHAR)");
            statement.execute("INSERT INTO \"" + TABLE + "\" VALUES (2, '001'), (3, '002'), (4, '003')");
        }
        return new Dataset(duck, TABLE, List.of("commune"));
    }

    private static ByteArrayInputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
