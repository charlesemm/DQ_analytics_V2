package ci.cnam.dqanalyzer.execution;

import static org.assertj.core.api.Assertions.assertThat;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import ci.cnam.dqanalyzer.reference.ReferenceTableService;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.RuleCatalogService;
import ci.cnam.dqanalyzer.rules.Severity;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import ci.cnam.dqanalyzer.templates.TemplateService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Exécution d'une analyse de bout en bout (EXE-01, EXE-02, CDC §7), sur le jeu de recette
 * {@code docs/recette/assures.csv} recopié dans les ressources de test.
 *
 * <p><strong>Les scores attendus sont calculés à la main</strong> (critère de recette « les scores
 * correspondent au calcul manuel »), avec les poids de sévérité du CDC : Bloquante 3, Majeure 2,
 * Mineure 1.
 *
 * <pre>
 * CMP-01 « Sexe » (Bloquante, seuil 100) : 12 contrôlées, 11 conformes → 1100/12 = 91,666… → 91,67
 *                                           91,67 &lt; 100 et Bloquante           → KO
 * VAL-04 « Salaire » min 0 (Majeure, seuil 100) : 11 contrôlées (ligne 12 vide), 10 conformes
 *                                           → 1000/11 = 90,909… → 90,91 → ALERTE
 * UNI-01 « Matricule » (Majeure, seuil 100) : 12 contrôlées, 10 conformes (lignes 5 et 6 en doublon)
 *                                           → 1000/12 = 83,333… → 83,33 → ALERTE
 *
 * Dimensions (une seule règle chacune, la pondération par sévérité ne change rien) :
 *   Complétude 91,666…   Validité 90,909…   Unicité 83,333…
 *
 * Score global, poids du modèle Complétude 3, Validité 2, Unicité 1 :
 *   (3 × 91,6666… + 2 × 90,9090… + 1 × 83,3333…) / 6
 *   = (275,0000… + 181,8181… + 83,3333…) / 6 = 540,1515… / 6 = 90,0252… → 90,03
 *
 * Statut global : KO, parce qu'une règle Bloquante est KO.
 * Anomalies enregistrées : 1 (CMP-01) + 1 (VAL-04) + 2 (UNI-01) = 4
 * </pre>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalysisRunnerIT {

    private static final String TABLE = "ds_analyse";
    private static final String OWNER = "Direction des prestations (analyse)";
    private static final String TEMPLATE = "Modèle d'analyse (test exécution)";

    @TempDir
    static Path directory;

    @Autowired
    AnalysisService analyses;

    @Autowired
    RuleCatalogService catalog;

    @Autowired
    TemplateService templates;

    @Autowired
    ReferenceTableService references;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;
    private long sourceFileId;

    @BeforeEach
    void preparerUnFichierCharge() throws Exception {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-execution', 'Analyste (exécution)', 'analyste-execution@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST')
                ON CONFLICT (login) DO NOTHING
                """);
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-execution'", Long.class);

        Path database = directory.resolve("analyse.duckdb");
        if (!Files.exists(database)) {
            Path csv = directory.resolve("assures.csv");
            Files.copy(
                    Objects.requireNonNull(
                            getClass().getClassLoader().getResourceAsStream("datasets/recette_assures.csv")),
                    csv);
            try (var connection = new DuckDbDatabases().open(database);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"" + TABLE + "\" AS SELECT row_number() OVER () + 1 AS \"_row_num\","
                        + " * FROM read_csv('"
                        + csv.toAbsolutePath().toString().replace('\\', '/')
                        + "', delim=';', header=true, all_varchar=true)");
            }
        }
        sourceFileId = jdbc.queryForObject(
                """
                INSERT INTO source_file (original_name, size_bytes, sha256, format, import_settings,
                                         storage_path, uploaded_by, import_status, import_progress,
                                         row_count, column_count, ragged_row_count, duckdb_path, table_name)
                VALUES ('assures.csv', 600, repeat('d', 64), 'DELIMITED', '{}'::jsonb,
                        '/tmp/assures.csv', ?, 'DONE', 100, 12, 6, 0, ?, ?)
                RETURNING id
                """,
                Long.class,
                userId,
                database.toString(),
                TABLE);

        nettoyerLeModele();
        jdbc.update("DELETE FROM dq_rule WHERE business_owner = ?", OWNER);
    }

    @Test
    void une_analyse_complete_donne_les_scores_du_calcul_manuel_et_le_statut_KO() throws Exception {
        long templateId = unModeleDeTroisRegles();

        long runId = analyses.start(sourceFileId, templateId, userId);
        var progress = attendreLaFin(runId);

        assertThat(progress.status()).isEqualTo("DONE");
        assertThat(progress.progress()).isEqualTo(100);
        assertThat(progress.resultStatus()).isEqualTo("KO");
        assertThat(progress.globalScore()).isEqualTo(90.03);

        var resultats = jdbc.queryForList(
                """
                SELECT r.name, res.checked_count, res.conforming_count, res.score, res.status,
                       res.duplicate_groups, res.anomalies_truncated
                FROM rule_result res JOIN dq_rule r ON r.id = res.rule_id
                WHERE res.run_id = ? ORDER BY r.name
                """,
                runId);
        assertThat(resultats).hasSize(3);
        assertThat(resultats.get(0))
                .containsEntry("name", "Matricule unique")
                .containsEntry("checked_count", 12L)
                .containsEntry("conforming_count", 10L)
                .containsEntry("status", "ALERT")
                .containsEntry("duplicate_groups", 1L)
                .containsEntry("anomalies_truncated", false);
        assertThat(((Number) resultats.get(0).get("score")).doubleValue()).isEqualTo(83.33);
        assertThat(resultats.get(1))
                .containsEntry("name", "Salaire positif")
                .containsEntry("checked_count", 11L)
                .containsEntry("conforming_count", 10L)
                .containsEntry("status", "ALERT");
        assertThat(((Number) resultats.get(1).get("score")).doubleValue()).isEqualTo(90.91);
        assertThat(resultats.get(2))
                .containsEntry("name", "Sexe obligatoire")
                .containsEntry("checked_count", 12L)
                .containsEntry("conforming_count", 11L)
                .containsEntry("status", "KO");
        assertThat(((Number) resultats.get(2).get("score")).doubleValue()).isEqualTo(91.67);
    }

    @Test
    void les_anomalies_sont_enregistrees_avec_leur_numero_de_ligne_d_origine() throws Exception {
        long templateId = unModeleDeTroisRegles();

        long runId = analyses.start(sourceFileId, templateId, userId);
        attendreLaFin(runId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(4);
        assertThat(jdbc.queryForList(
                        """
                        SELECT a.row_num FROM anomaly a JOIN dq_rule r ON r.id = a.rule_id
                        WHERE a.run_id = ? AND r.name = 'Matricule unique' ORDER BY a.row_num
                        """,
                        Long.class,
                        runId))
                .containsExactly(5L, 6L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT a.row_num FROM anomaly a JOIN dq_rule r ON r.id = a.rule_id
                        WHERE a.run_id = ? AND r.name = 'Sexe obligatoire'
                        """,
                        Long.class,
                        runId))
                .isEqualTo(4L);
    }

    @Test
    void la_version_du_referentiel_utilisee_est_figee_dans_run_reference() throws Exception {
        long referenceId = references
                .load(
                        "Communes (test exécution)",
                        new ByteArrayInputStream(
                                "code;nom\n001;PLATEAU\n003;YOPOUGON\n004;MARCORY\n".getBytes(StandardCharsets.UTF_8)),
                        "communes.csv",
                        userId)
                .id();
        String code = catalog.create(
                new RuleCatalogService.RuleForm(
                        "Commune connue",
                        null,
                        "INT-01",
                        List.of("Commune"),
                        Map.of("referenceTableId", referenceId, "referenceColumn", "code"),
                        Normalization.NONE,
                        Severity.MAJOR,
                        100.0,
                        null,
                        OWNER,
                        "Création"),
                userId);
        long templateId =
                unModele(List.of(code), Map.of("Commune", "commune"), Map.of(Dimension.REFERENTIAL_INTEGRITY, 1));

        long runId = analyses.start(sourceFileId, templateId, userId);
        var progress = attendreLaFin(runId);

        assertThat(progress.status()).isEqualTo("DONE");
        assertThat(jdbc.queryForList(
                        "SELECT reference_table_id FROM run_reference WHERE run_id = ?", Long.class, runId))
                .containsExactly(referenceId);
        // Les 5 lignes dont la commune vaut 002, retirée de cette version du référentiel.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE run_id = ?", Integer.class, runId))
                .isEqualTo(5);
    }

    @Test
    void une_regle_qui_cite_une_colonne_absente_fait_echouer_l_analyse_avec_un_message_lisible() throws Exception {
        String code = catalog.create(
                new RuleCatalogService.RuleForm(
                        "Téléphone obligatoire",
                        null,
                        "CMP-01",
                        List.of("telephone"),
                        Map.of(),
                        Normalization.NONE,
                        Severity.MAJOR,
                        100.0,
                        null,
                        OWNER,
                        "Création"),
                userId);
        long templateId = unModele(List.of(code), Map.of(), Map.of(Dimension.COMPLETENESS, 1));

        long runId = analyses.start(sourceFileId, templateId, userId);
        var progress = attendreLaFin(runId);

        assertThat(progress.status()).isEqualTo("FAILED");
        assertThat(progress.error()).contains("telephone").contains("n'existe pas dans le fichier");
        // Rien de partiel ne reste : ni résultat, ni anomalie.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rule_result WHERE run_id = ?", Integer.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE run_id = ?", Integer.class, runId))
                .isZero();
    }

    @Test
    void un_modele_sans_regle_active_echoue_avec_un_message_clair() throws Exception {
        long templateId = unModele(List.of(), Map.of(), Map.of(Dimension.COMPLETENESS, 1));

        long runId = analyses.start(sourceFileId, templateId, userId);
        var progress = attendreLaFin(runId);

        assertThat(progress.status()).isEqualTo("FAILED");
        assertThat(progress.error()).contains("aucune règle active");
    }

    /** Les trois règles du calcul manuel, avec les colonnes nommées comme dans le modèle. */
    private long unModeleDeTroisRegles() {
        String sexe = catalog.create(regle("Sexe obligatoire", "CMP-01", "Sexe", Map.of(), Severity.BLOCKING), userId);
        String salaire = catalog.create(
                regle("Salaire positif", "VAL-04", "Salaire", Map.of("min", "0"), Severity.MAJOR), userId);
        String matricule =
                catalog.create(regle("Matricule unique", "UNI-01", "Matricule", Map.of(), Severity.MAJOR), userId);
        return unModele(
                List.of(sexe, salaire, matricule),
                Map.of("Sexe", "sexe", "Salaire", "salaire", "Matricule", "matricule"),
                Map.of(Dimension.COMPLETENESS, 3, Dimension.VALIDITY, 2, Dimension.UNIQUENESS, 1));
    }

    private RuleCatalogService.RuleForm regle(
            String name, String type, String column, Map<String, Object> params, Severity severity) {
        return new RuleCatalogService.RuleForm(
                name,
                null,
                type,
                List.of(column),
                params,
                Normalization.NONE,
                severity,
                100.0,
                null,
                OWNER,
                "Création");
    }

    /** Les analyses deja lancees referencent le modele : elles partent avec lui (cascade sur leurs resultats). */
    private void nettoyerLeModele() {
        jdbc.update(
                "DELETE FROM analysis_run WHERE template_id IN (SELECT id FROM analysis_template WHERE name = ?)",
                TEMPLATE);
        jdbc.update("DELETE FROM analysis_template WHERE name = ?", TEMPLATE);
    }

    private long unModele(List<String> ruleCodes, Map<String, String> mapping, Map<Dimension, Integer> weights) {
        nettoyerLeModele();
        long templateId = templates.create(
                new TemplateService.TemplateForm(TEMPLATE, null, List.copyOf(mapping.keySet()), weights, ruleCodes),
                userId);
        templates.saveColumnMapping(templateId, mapping);
        return templateId;
    }

    /** L'analyse tourne en tâche de fond : on attend qu'elle se termine, au plus 30 secondes. */
    private AnalysisService.Progress attendreLaFin(long runId) throws InterruptedException {
        for (int attempt = 0; attempt < 600; attempt++) {
            var progress = analyses.progress(runId);
            if (progress.finished()) {
                return progress;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("L'analyse " + runId + " ne s'est pas terminée dans le délai imparti");
    }
}
