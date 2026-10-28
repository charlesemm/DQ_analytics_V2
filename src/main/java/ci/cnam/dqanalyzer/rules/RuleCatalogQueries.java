package ci.cnam.dqanalyzer.rules;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Lectures du catalogue pour les écrans : liste filtrable des règles et historique de leurs versions
 * (REG-06, REG-04). Les filtres laissés vides ne restreignent rien ; aucune valeur saisie n'entre
 * dans le texte de la requête, tout passe par des paramètres.
 */
@Component
public class RuleCatalogQueries {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private static final String SELECT_SUMMARY =
            """
            SELECT r.rule_code, r.version, r.name, r.rule_type, r.dimension, r.severity,
                   r.target_columns::text, r.active, r.business_owner, r.validated_by, r.validated_on,
                   u.full_name, r.change_comment
            FROM dq_rule r
            JOIN app_user u ON u.id = r.created_by
            """;

    private final JdbcTemplate jdbc;
    private final RowMapper<RuleSummary> mapper;

    public RuleCatalogQueries(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.mapper = (rs, i) -> new RuleSummary(
                rs.getString(1),
                rs.getInt(2),
                rs.getString(3),
                rs.getString(4),
                Dimension.valueOf(rs.getString(5)),
                Severity.valueOf(rs.getString(6)),
                json.readValue(rs.getString(7), STRINGS),
                rs.getBoolean(8),
                rs.getString(9),
                rs.getString(10),
                rs.getDate(11) == null ? null : rs.getDate(11).toLocalDate(),
                rs.getString(12),
                rs.getString(13));
    }

    /**
     * Ce que l'analyste peut combiner dans le bandeau de filtres. Un champ {@code null} ou vide ne
     * filtre pas.
     *
     * @param active trois états : vrai (actives), faux (désactivées), null (toutes)
     * @param templateId limite aux règles retenues par un modèle d'analyse
     */
    public record RuleFilter(
            Dimension dimension, String businessOwner, Long authorId, Boolean active, Long templateId) {

        public static final RuleFilter ALL = new RuleFilter(null, null, null, null, null);
    }

    /** Version courante de chaque règle, filtrée, la plus récemment créée d'abord. */
    public List<RuleSummary> list(RuleFilter filter) {
        String owner = blankToNull(filter.businessOwner());
        return jdbc.query(
                SELECT_SUMMARY
                        + """
                        WHERE r.is_current
                          AND (CAST(? AS VARCHAR) IS NULL OR r.dimension = CAST(? AS VARCHAR))
                          AND (CAST(? AS VARCHAR) IS NULL OR r.business_owner = CAST(? AS VARCHAR))
                          AND (CAST(? AS BIGINT) IS NULL OR r.created_by = CAST(? AS BIGINT))
                          AND (CAST(? AS BOOLEAN) IS NULL OR r.active = CAST(? AS BOOLEAN))
                          AND (CAST(? AS BIGINT) IS NULL OR EXISTS (
                                  SELECT 1 FROM template_rule t
                                  WHERE t.rule_code = r.rule_code AND t.template_id = CAST(? AS BIGINT)))
                        ORDER BY r.created_at DESC, r.rule_code DESC
                        """,
                mapper,
                name(filter.dimension()),
                name(filter.dimension()),
                owner,
                owner,
                filter.authorId(),
                filter.authorId(),
                filter.active(),
                filter.active(),
                filter.templateId(),
                filter.templateId());
    }

    /** Toutes les versions d'une règle, de la plus récente à la plus ancienne (REG-04). */
    public List<RuleSummary> history(String ruleCode) {
        return jdbc.query(SELECT_SUMMARY + " WHERE r.rule_code = ? ORDER BY r.version DESC", mapper, ruleCode);
    }

    /** Propriétaires métier déjà saisis, pour proposer une liste plutôt qu'un champ libre. */
    public List<String> businessOwners() {
        return jdbc.queryForList(
                """
                SELECT DISTINCT business_owner FROM dq_rule
                WHERE business_owner IS NOT NULL AND business_owner <> ''
                ORDER BY business_owner
                """,
                String.class);
    }

    /** Auteurs de règles : identifiant et nom complet, pour le filtre par auteur. */
    public List<Author> authors() {
        return jdbc.query(
                """
                SELECT DISTINCT u.id, u.full_name FROM dq_rule r
                JOIN app_user u ON u.id = r.created_by
                ORDER BY u.full_name
                """,
                (rs, i) -> new Author(rs.getLong(1), rs.getString(2)));
    }

    public record Author(long id, String fullName) {}

    private static String name(Dimension dimension) {
        return dimension == null ? null : dimension.name();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
