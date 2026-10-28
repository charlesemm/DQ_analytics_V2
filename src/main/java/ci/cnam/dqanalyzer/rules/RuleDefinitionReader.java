package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Lit dans PostgreSQL les règles enregistrées et les transforme en {@link RuleDefinition}.
 * L'identifiant renvoyé est celui de la ligne de version : il sera enregistré dans rule_result, ce
 * qui fige la version exacte utilisée par une analyse (TRA-02).
 */
@Component
public class RuleDefinitionReader {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private static final String COLUMNS =
            """
            r.id, r.rule_code, r.version, r.name, r.rule_type, r.dimension, r.target_columns::text,
            r.params::text, r.normalization::text, r.severity, r.threshold, r.message
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<RuleDefinition> mapper;

    public RuleDefinitionReader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.mapper = (rs, i) -> {
            Map<String, Object> norm = json.readValue(rs.getString(9), OBJECT);
            return new RuleDefinition(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getString(4),
                    rs.getString(5),
                    Dimension.valueOf(rs.getString(6)),
                    json.readValue(rs.getString(7), STRINGS),
                    json.readValue(rs.getString(8), OBJECT),
                    new Normalization(
                            Boolean.TRUE.equals(norm.get("trim")),
                            Boolean.TRUE.equals(norm.get("upperCase")),
                            Boolean.TRUE.equals(norm.get("stripAccents"))),
                    Severity.valueOf(rs.getString(10)),
                    rs.getDouble(11),
                    rs.getString(12));
        };
    }

    /** Règles d'un modèle, dans leur version courante et active, dans l'ordre du modèle. */
    public List<RuleDefinition> forTemplate(long templateId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + """
                FROM template_rule t
                JOIN dq_rule r ON r.rule_code = t.rule_code AND r.is_current AND r.active
                WHERE t.template_id = ?
                ORDER BY t.position, r.rule_code
                """,
                mapper,
                templateId);
    }

    /** Version courante d'une règle, désactivée ou non : ce que le formulaire réaffiche. */
    public RuleDefinition current(String ruleCode) {
        return jdbc
                .query("SELECT " + COLUMNS + " FROM dq_rule r WHERE r.rule_code = ? AND r.is_current", mapper, ruleCode)
                .stream()
                .findFirst()
                .orElseThrow(() -> new RuleEvaluationException("La règle " + ruleCode + " n'existe pas"));
    }

    /** Toutes les versions d'une règle, de la plus récente à la plus ancienne (REG-04). */
    public List<RuleDefinition> versions(String ruleCode) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM dq_rule r WHERE r.rule_code = ? ORDER BY r.version DESC",
                mapper,
                ruleCode);
    }
}
