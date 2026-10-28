package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Création et modification des règles, avec versions (REG-01, REG-04). Une modification ne remplace
 * jamais une règle : elle crée une nouvelle ligne (version + 1) et retire le statut « courant » de
 * l'ancienne. Les analyses passées gardent donc la version qu'elles ont utilisée (TRA-02).
 */
@Service
public class RuleCatalogService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RuleEvaluatorRegistry registry;

    public RuleCatalogService(JdbcTemplate jdbc, ObjectMapper json, RuleEvaluatorRegistry registry) {
        this.jdbc = jdbc;
        this.json = json;
        this.registry = registry;
    }

    /** Ce que l'analyste saisit dans le formulaire de règle. */
    public record RuleForm(
            String name,
            String description,
            String type,
            List<String> targetColumns,
            Map<String, Object> params,
            Normalization normalization,
            Severity severity,
            double thresholdPercent,
            String message,
            String businessOwner,
            String changeComment) {}

    /** Crée la version 1 d'une nouvelle règle et renvoie son code (R-0001…). */
    @Transactional
    public String create(RuleForm form, long userId) {
        var evaluator = registry.get(form.type());
        String code =
                jdbc.queryForObject("SELECT 'R-' || lpad(nextval('dq_rule_code_seq')::text, 4, '0')", String.class);
        insertVersion(code, 1, form, evaluator.dimension(), userId);
        return code;
    }

    /** Modifie une règle : nouvelle version, l'ancienne reste en base pour l'historique. */
    @Transactional
    public int update(String ruleCode, RuleForm form, long userId) {
        var evaluator = registry.get(form.type());
        Integer current = jdbc.queryForObject(
                "SELECT version FROM dq_rule WHERE rule_code = ? AND is_current FOR UPDATE", Integer.class, ruleCode);
        if (current == null) {
            throw new RuleEvaluationException("La règle " + ruleCode + " n'existe pas");
        }
        jdbc.update("UPDATE dq_rule SET is_current = FALSE WHERE rule_code = ? AND is_current", ruleCode);
        insertVersion(ruleCode, current + 1, form, evaluator.dimension(), userId);
        return current + 1;
    }

    /** Active ou désactive une règle sans créer de version : elle n'est simplement plus exécutée. */
    @Transactional
    public void setActive(String ruleCode, boolean active) {
        jdbc.update("UPDATE dq_rule SET active = ? WHERE rule_code = ? AND is_current", active, ruleCode);
    }

    /** Enregistre la validation de la version courante par la direction métier propriétaire. */
    @Transactional
    public void validate(String ruleCode, String validatedBy) {
        jdbc.update(
                "UPDATE dq_rule SET validated_by = ?, validated_on = current_date WHERE rule_code = ? AND is_current",
                validatedBy,
                ruleCode);
    }

    private void insertVersion(String code, int version, RuleForm form, Dimension dimension, long userId) {
        var norm = form.normalization() == null ? Normalization.NONE : form.normalization();
        jdbc.update(
                """
                INSERT INTO dq_rule (rule_code, version, is_current, name, description, dimension, rule_type,
                                     target_columns, params, normalization, severity, threshold, message,
                                     created_by, business_owner, change_comment)
                VALUES (?, ?, TRUE, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """,
                code,
                version,
                form.name(),
                form.description(),
                dimension.name(),
                form.type(),
                json.writeValueAsString(form.targetColumns() == null ? List.of() : form.targetColumns()),
                json.writeValueAsString(form.params() == null ? Map.of() : form.params()),
                json.writeValueAsString(Map.of(
                        "trim", norm.trim(), "upperCase", norm.upperCase(), "stripAccents", norm.stripAccents())),
                form.severity().name(),
                form.thresholdPercent(),
                form.message(),
                userId,
                form.businessOwner(),
                form.changeComment());
    }
}
