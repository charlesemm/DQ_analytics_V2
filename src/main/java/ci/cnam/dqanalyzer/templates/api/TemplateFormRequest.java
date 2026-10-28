package ci.cnam.dqanalyzer.templates.api;

import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.templates.AnalysisTemplate;
import ci.cnam.dqanalyzer.templates.TemplateService.TemplateForm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le formulaire de modèle tel que le navigateur le renvoie (MOD-01). Classe modifiable, et non
 * record, parce que Spring la remplit champ par champ et que l'écran la réaffiche telle quelle.
 *
 * <p>Les colonnes attendues sont saisies une par ligne : c'est la forme sous laquelle l'analyste les
 * copie depuis l'en-tête de son fichier.
 */
public class TemplateFormRequest {

    private String name;
    private String description;
    private String expectedColumns = "";
    private List<String> ruleCodes = new ArrayList<>();
    private Map<String, Integer> weights = new LinkedHashMap<>();

    /** Formulaire prérempli avec un modèle existant. */
    public static TemplateFormRequest of(AnalysisTemplate template) {
        var request = new TemplateFormRequest();
        request.setName(template.name());
        request.setDescription(template.description());
        request.setExpectedColumns(String.join("\n", template.expectedColumns()));
        request.setRuleCodes(new ArrayList<>(template.ruleCodes()));
        template.dimensionWeights()
                .forEach((dimension, weight) -> request.getWeights().put(dimension.name(), weight));
        return request;
    }

    public TemplateForm toTemplateForm() {
        var dimensionWeights = new LinkedHashMap<Dimension, Integer>();
        weights.forEach((name, weight) -> {
            if (weight != null) {
                dimensionWeights.put(Dimension.valueOf(name), weight);
            }
        });
        return new TemplateForm(
                name == null ? null : name.trim(),
                description == null || description.isBlank() ? null : description,
                columns(),
                dimensionWeights,
                ruleCodes.stream()
                        .filter(code -> code != null && !code.isBlank())
                        .toList());
    }

    /** Une colonne par ligne, les lignes vides et les espaces de bord retirés. */
    public List<String> columns() {
        return expectedColumns
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .distinct()
                .toList();
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

    public String getExpectedColumns() {
        return expectedColumns;
    }

    public void setExpectedColumns(String expectedColumns) {
        this.expectedColumns = expectedColumns == null ? "" : expectedColumns;
    }

    public List<String> getRuleCodes() {
        return ruleCodes;
    }

    public void setRuleCodes(List<String> ruleCodes) {
        this.ruleCodes = ruleCodes == null ? new ArrayList<>() : ruleCodes;
    }

    public Map<String, Integer> getWeights() {
        return weights;
    }

    public void setWeights(Map<String, Integer> weights) {
        this.weights = weights == null ? new LinkedHashMap<>() : weights;
    }
}
