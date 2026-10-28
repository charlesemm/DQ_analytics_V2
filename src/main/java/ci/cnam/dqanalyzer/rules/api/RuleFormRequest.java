package ci.cnam.dqanalyzer.rules.api;

import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.RuleCatalogService.RuleForm;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleSummary;
import ci.cnam.dqanalyzer.rules.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Le formulaire de règle tel que le navigateur le renvoie (REG-01, REG-02). Classe modifiable, et
 * non record, parce que Spring la remplit champ par champ et que l'écran la réaffiche telle quelle
 * quand la saisie est refusée.
 *
 * <p>Les paramètres arrivent dans {@code params[nom]}, en texte. Les paramètres de type EXPRESSION
 * (condition SI, contrôle ALORS, calcul attendu) arrivent sous forme de JSON construit par
 * l'éditeur de l'écran : il n'y a pas de texte libre, et ce JSON est de toute façon revalidé par
 * {@code ConditionParser} ou {@code NumericExpression} avant exécution.
 */
public class RuleFormRequest {

    /** Paramètres dont la valeur est un arbre JSON et non une valeur simple. */
    public static final List<String> EXPRESSION_PARAMS = List.of("when", "then", "expression");

    private String name;
    private String description;
    private String type;
    private List<String> targetColumns = new ArrayList<>();
    private Map<String, String> params = new LinkedHashMap<>();
    private boolean trim;
    private boolean upperCase;
    private boolean stripAccents;
    private Severity severity = Severity.MAJOR;
    private double thresholdPercent = 100;
    private String message;
    private String businessOwner;
    private String changeComment;

    /**
     * Formulaire prérempli avec une règle existante, pour la modifier ou la dupliquer. Les listes de
     * valeurs reviennent en « a; b », comme l'analyste les a saisies, et les arbres JSON en texte,
     * tel que l'éditeur de conditions les relit.
     */
    static RuleFormRequest of(RuleDefinition rule, RuleSummary summary, ObjectMapper json) {
        var request = new RuleFormRequest();
        request.setName(rule.name());
        request.setType(rule.type());
        request.setTargetColumns(new ArrayList<>(rule.targetColumns()));
        request.setSeverity(rule.severity());
        request.setThresholdPercent(rule.thresholdPercent());
        request.setMessage(rule.message());
        request.setTrim(rule.normalization().trim());
        request.setUpperCase(rule.normalization().upperCase());
        request.setStripAccents(rule.normalization().stripAccents());
        request.setBusinessOwner(summary.businessOwner());
        rule.params().forEach((key, value) -> request.getParams().put(key, text(json, key, value)));
        return request;
    }

    private static String text(ObjectMapper json, String name, Object value) {
        if (EXPRESSION_PARAMS.contains(name)) {
            return json.writeValueAsString(value);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining("; "));
        }
        return String.valueOf(value);
    }

    /** Traduit la saisie en {@link RuleForm}, le contrat du catalogue. */
    RuleForm toRuleForm(ObjectMapper json) {
        var values = new LinkedHashMap<String, Object>();
        params.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            values.put(key, EXPRESSION_PARAMS.contains(key) ? tree(json, key, value) : value);
        });
        return new RuleForm(
                blankToNull(name),
                blankToNull(description),
                type,
                targetColumns.stream().filter(c -> c != null && !c.isBlank()).toList(),
                values,
                new Normalization(trim, upperCase, stripAccents),
                severity,
                thresholdPercent,
                blankToNull(message),
                blankToNull(businessOwner),
                blankToNull(changeComment));
    }

    private static Object tree(ObjectMapper json, String name, String value) {
        try {
            return json.readValue(value, Object.class);
        } catch (JacksonException e) {
            throw new InvalidRuleFormException("rules.form.error.expression", name);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<String> getTargetColumns() {
        return targetColumns;
    }

    public void setTargetColumns(List<String> targetColumns) {
        this.targetColumns = targetColumns == null ? new ArrayList<>() : targetColumns;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public void setParams(Map<String, String> params) {
        this.params = params == null ? new LinkedHashMap<>() : params;
    }

    public boolean isTrim() {
        return trim;
    }

    public void setTrim(boolean trim) {
        this.trim = trim;
    }

    public boolean isUpperCase() {
        return upperCase;
    }

    public void setUpperCase(boolean upperCase) {
        this.upperCase = upperCase;
    }

    public boolean isStripAccents() {
        return stripAccents;
    }

    public void setStripAccents(boolean stripAccents) {
        this.stripAccents = stripAccents;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public double getThresholdPercent() {
        return thresholdPercent;
    }

    public void setThresholdPercent(double thresholdPercent) {
        this.thresholdPercent = thresholdPercent;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getBusinessOwner() {
        return businessOwner;
    }

    public void setBusinessOwner(String businessOwner) {
        this.businessOwner = businessOwner;
    }

    public String getChangeComment() {
        return changeComment;
    }

    public void setChangeComment(String changeComment) {
        this.changeComment = changeComment;
    }
}
