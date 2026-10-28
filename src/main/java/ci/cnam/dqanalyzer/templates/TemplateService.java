package ci.cnam.dqanalyzer.templates;

import ci.cnam.dqanalyzer.rules.Dimension;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Modèles d'analyse (MOD-01 à MOD-03) : composition d'un modèle, règles retenues et ordonnées, et
 * correspondance des colonnes avec un fichier.
 *
 * <p>Un modèle désigne les règles par leur code, jamais par une version : une analyse lancée demain
 * prendra la version en vigueur de chaque règle (TRA-02).
 */
@Service
public class TemplateService {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public TemplateService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Ce que l'analyste saisit dans le formulaire de modèle. */
    public record TemplateForm(
            String name,
            String description,
            List<String> expectedColumns,
            Map<Dimension, Integer> dimensionWeights,
            List<String> ruleCodes) {}

    /** Une ligne de la liste des modèles. */
    public record TemplateSummary(
            long id, String name, String description, int expectedColumnCount, int ruleCount, boolean mapped) {}

    public List<TemplateSummary> list() {
        return jdbc.query(
                """
                SELECT t.id, t.name, t.description,
                       jsonb_array_length(t.expected_columns) AS columns,
                       (SELECT count(*) FROM template_rule r WHERE r.template_id = t.id) AS rules,
                       t.column_mapping <> '{}'::jsonb AS mapped
                FROM analysis_template t
                ORDER BY t.name
                """,
                (rs, i) -> new TemplateSummary(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getBoolean(6)));
    }

    /** @throws NoSuchElementException si le modèle n'existe pas */
    public AnalysisTemplate byId(long templateId) {
        var template = jdbc
                .query(
                        """
                        SELECT id, name, description, expected_columns::text, dimension_weights::text,
                               column_mapping::text
                        FROM analysis_template WHERE id = ?
                        """,
                        (rs, i) -> new AnalysisTemplate(
                                rs.getLong(1),
                                rs.getString(2),
                                rs.getString(3),
                                json.readValue(rs.getString(4), STRINGS),
                                weights(rs.getString(5)),
                                ruleCodesOf(rs.getLong(1)),
                                mapping(rs.getString(6))),
                        templateId)
                .stream()
                .findFirst();
        return template.orElseThrow(() -> new NoSuchElementException("Modèle inconnu : " + templateId));
    }

    @Transactional
    public long create(TemplateForm form, long userId) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO analysis_template (name, description, expected_columns, dimension_weights, created_by)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?)
                RETURNING id
                """,
                Long.class,
                form.name(),
                form.description(),
                json.writeValueAsString(form.expectedColumns()),
                json.writeValueAsString(form.dimensionWeights()),
                userId);
        replaceRules(id, form.ruleCodes());
        return id;
    }

    @Transactional
    public void update(long templateId, TemplateForm form) {
        int updated = jdbc.update(
                """
                UPDATE analysis_template
                SET name = ?, description = ?, expected_columns = ?::jsonb, dimension_weights = ?::jsonb,
                    updated_at = now()
                WHERE id = ?
                """,
                form.name(),
                form.description(),
                json.writeValueAsString(form.expectedColumns()),
                json.writeValueAsString(form.dimensionWeights()),
                templateId);
        if (updated == 0) {
            throw new NoSuchElementException("Modèle inconnu : " + templateId);
        }
        replaceRules(templateId, form.ruleCodes());
    }

    /**
     * Correspondance proposée entre les colonnes attendues du modèle et celles d'un fichier (MOD-02).
     * Ce qui a déjà été corrigé à la main est conservé : la proposition ne défait pas un choix de
     * l'analyste, tant que la colonne existe encore dans le fichier.
     */
    public Map<String, String> proposeMapping(long templateId, List<String> fileColumns) {
        var template = byId(templateId);
        var proposed = ColumnMatcher.match(template.expectedColumns(), fileColumns);
        var result = new LinkedHashMap<String, String>();
        proposed.forEach((expected, match) -> {
            String known = template.columnMapping().get(expected);
            result.put(expected, known != null && fileColumns.contains(known) ? known : match);
        });
        return result;
    }

    /** Enregistre la correspondance retenue par l'analyste (MOD-03). */
    @Transactional
    public void saveColumnMapping(long templateId, Map<String, String> mapping) {
        var kept = new LinkedHashMap<String, String>();
        mapping.forEach((expected, column) -> {
            if (column != null && !column.isBlank()) {
                kept.put(expected, column);
            }
        });
        int updated = jdbc.update(
                "UPDATE analysis_template SET column_mapping = ?::jsonb, updated_at = now() WHERE id = ?",
                json.writeValueAsString(kept),
                templateId);
        if (updated == 0) {
            throw new NoSuchElementException("Modèle inconnu : " + templateId);
        }
    }

    private void replaceRules(long templateId, List<String> ruleCodes) {
        jdbc.update("DELETE FROM template_rule WHERE template_id = ?", templateId);
        int position = 0;
        for (String ruleCode : ruleCodes) {
            jdbc.update(
                    "INSERT INTO template_rule (template_id, rule_code, position) VALUES (?, ?, ?)",
                    templateId,
                    ruleCode,
                    position++);
        }
    }

    private List<String> ruleCodesOf(long templateId) {
        return jdbc.queryForList(
                "SELECT rule_code FROM template_rule WHERE template_id = ? ORDER BY position, rule_code",
                String.class,
                templateId);
    }

    private Map<Dimension, Integer> weights(String stored) {
        Map<String, Object> raw = json.readValue(stored, OBJECT);
        var weights = new LinkedHashMap<Dimension, Integer>();
        raw.forEach((name, value) -> {
            try {
                weights.put(Dimension.valueOf(name), (int) Double.parseDouble(String.valueOf(value)));
            } catch (IllegalArgumentException e) {
                // Dimension disparue d'une version précédente : on l'ignore, le poids par défaut vaut 1.
            }
        });
        return weights;
    }

    private Map<String, String> mapping(String stored) {
        Map<String, Object> raw = json.readValue(stored, OBJECT);
        var mapping = new LinkedHashMap<String, String>();
        raw.forEach((expected, column) -> mapping.put(expected, String.valueOf(column)));
        return mapping;
    }
}
