package ci.cnam.dqanalyzer.rules;

import static org.assertj.core.api.Assertions.assertThat;

import ci.cnam.dqanalyzer.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Versions des règles sur un PostgreSQL jetable (REG-01, REG-04, TRA-02) : modifier une règle crée la
 * version suivante et laisse la précédente en base, visible dans l'historique.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleCatalogServiceIT {

    @Autowired
    RuleCatalogService service;

    @Autowired
    RuleDefinitionReader reader;

    @Autowired
    JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void creerLeCompte() {
        jdbc.update(
                """
                INSERT INTO app_user (login, full_name, email, password_hash, role)
                VALUES ('analyste-regles', 'Analyste (test)', 'analyste-regles@dq.local',
                        '$2a$10$abcdefghijklmnopqrstuv', 'ANALYST')
                ON CONFLICT (login) DO NOTHING
                """);
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'analyste-regles'", Long.class);
    }

    @Test
    void une_creation_numerote_la_regle_et_prend_la_dimension_de_l_evaluateur() {
        String code = service.create(form("Numéro de sécurité obligatoire", "CMP-01", 1), userId);

        assertThat(code).matches("R-\\d{4}");
        var rule = reader.current(code);
        assertThat(rule.version()).isEqualTo(1);
        assertThat(rule.dimension()).isEqualTo(Dimension.COMPLETENESS);
        assertThat(rule.targetColumns()).containsExactly("num_secu");
        assertThat(rule.severity()).isEqualTo(Severity.BLOCKING);
    }

    @Test
    void une_modification_cree_la_version_2_et_garde_la_version_1_dans_l_historique() {
        String code = service.create(form("Libellé d'origine", "CMP-01", 1), userId);

        int version = service.update(code, form("Libellé corrigé", "CMP-01", 2), userId);

        assertThat(version).isEqualTo(2);
        assertThat(reader.current(code).name()).isEqualTo("Libellé corrigé");
        assertThat(reader.versions(code)).extracting(RuleDefinition::version).containsExactly(2, 1);
        assertThat(reader.versions(code))
                .extracting(RuleDefinition::name)
                .containsExactly("Libellé corrigé", "Libellé d'origine");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM dq_rule WHERE rule_code = ? AND is_current", Integer.class, code))
                .isEqualTo(1);
    }

    @Test
    void desactiver_une_regle_ne_cree_pas_de_version() {
        String code = service.create(form("Règle à désactiver", "CMP-01", 1), userId);

        service.setActive(code, false);

        assertThat(reader.versions(code)).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT active FROM dq_rule WHERE rule_code = ? AND is_current", Boolean.class, code))
                .isFalse();
    }

    @Test
    void la_validation_metier_est_tracee_sur_la_version_courante() {
        String code = service.create(form("Règle à valider", "CMP-01", 1), userId);

        service.validate(code, "Direction des prestations");

        var row = jdbc.queryForMap(
                "SELECT validated_by, validated_on, business_owner FROM dq_rule WHERE rule_code = ? AND is_current",
                code);
        assertThat(row.get("validated_by")).isEqualTo("Direction des prestations");
        assertThat(row.get("validated_on")).isNotNull();
        assertThat(row.get("business_owner")).isEqualTo("Direction des prestations");
    }

    @Test
    void les_parametres_sont_relus_tels_qu_ils_ont_ete_saisis() {
        String code = service.create(
                new RuleCatalogService.RuleForm(
                        "Sexe dans la liste",
                        "Valeurs autorisées M et F",
                        "VAL-05",
                        List.of("sexe"),
                        Map.of("allowedValues", List.of("M", "F")),
                        new Normalization(true, true, false),
                        Severity.MAJOR,
                        99.0,
                        "La valeur du sexe doit être M ou F.",
                        "Direction des prestations",
                        "Création"),
                userId);

        var rule = reader.current(code);
        assertThat(rule.params()).containsEntry("allowedValues", List.of("M", "F"));
        assertThat(rule.normalization()).isEqualTo(new Normalization(true, true, false));
        assertThat(rule.thresholdPercent()).isEqualTo(99.0);
    }

    private RuleCatalogService.RuleForm form(String name, String type, int version) {
        return new RuleCatalogService.RuleForm(
                name,
                "Règle de test, version " + version,
                type,
                List.of("num_secu"),
                Map.of(),
                Normalization.NONE,
                Severity.BLOCKING,
                100.0,
                "Le numéro de sécurité sociale est obligatoire.",
                "Direction des prestations",
                version == 1 ? "Création" : "Correction du libellé");
    }
}
