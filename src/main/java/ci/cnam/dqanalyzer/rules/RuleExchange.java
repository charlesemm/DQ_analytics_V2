package ci.cnam.dqanalyzer.rules;

import ci.cnam.dqanalyzer.rules.RuleCatalogQueries.RuleFilter;
import ci.cnam.dqanalyzer.rules.RuleCatalogService.RuleForm;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Export et import des règles en JSON (REG-05) : de quoi porter un jeu de règles d'un poste à un
 * autre, ou de la recette vers la production, sans ressaisie.
 *
 * <p>Une règle importée est toujours vérifiée avant d'être écrite, comme si elle venait du
 * formulaire. Les règles refusées sont listées dans le compte rendu, les autres sont enregistrées :
 * un fichier avec une erreur n'empêche pas d'importer le reste.
 */
@Component
public class RuleExchange {

    /**
     * Forme d'une règle dans le fichier d'échange. Le code et la version ne sont qu'informatifs : ils
     * sont réattribués à l'import. Les types sont volontairement « boxés » : un fichier écrit à la
     * main n'a pas à renseigner tous les champs, une valeur absente prend sa valeur par défaut.
     */
    public record RuleExport(
            String ruleCode,
            Integer version,
            String name,
            String description,
            String type,
            List<String> targetColumns,
            Map<String, Object> params,
            Boolean trim,
            Boolean upperCase,
            Boolean stripAccents,
            Severity severity,
            Double thresholdPercent,
            String message,
            String businessOwner) {}

    /**
     * Compte rendu d'un import.
     *
     * @param created règles enregistrées en version 1
     * @param updated règles déjà connues sous le même nom, enregistrées en version suivante
     * @param rejected règles refusées, avec la raison, en français
     */
    public record ImportReport(List<String> created, List<String> updated, List<Rejection> rejected) {

        public ImportReport {
            created = List.copyOf(created);
            updated = List.copyOf(updated);
            rejected = List.copyOf(rejected);
        }

        public int total() {
            return created.size() + updated.size() + rejected.size();
        }
    }

    public record Rejection(String name, String reason) {}

    private static final TypeReference<List<RuleExport>> RULES = new TypeReference<>() {};

    private final RuleCatalogQueries queries;
    private final RuleDefinitionReader reader;
    private final RuleCatalogService catalog;
    private final RuleFormValidator validator;
    private final ObjectMapper json;

    public RuleExchange(
            RuleCatalogQueries queries,
            RuleDefinitionReader reader,
            RuleCatalogService catalog,
            RuleFormValidator validator,
            ObjectMapper json) {
        this.queries = queries;
        this.reader = reader;
        this.catalog = catalog;
        this.validator = validator;
        this.json = json;
    }

    /** Version courante des règles retenues par le filtre, prête à être écrite en JSON. */
    public List<RuleExport> export(RuleFilter filter) {
        return queries.list(filter).stream()
                .map(summary -> {
                    var rule = reader.current(summary.ruleCode());
                    return new RuleExport(
                            rule.ruleCode(),
                            rule.version(),
                            rule.name(),
                            null,
                            rule.type(),
                            rule.targetColumns(),
                            rule.params(),
                            rule.normalization().trim(),
                            rule.normalization().upperCase(),
                            rule.normalization().stripAccents(),
                            rule.severity(),
                            rule.thresholdPercent(),
                            rule.message(),
                            summary.businessOwner());
                })
                .toList();
    }

    public String toJson(List<RuleExport> rules) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(rules);
    }

    /**
     * Lit un fichier d'échange et enregistre ce qui est valide. Une règle dont le nom existe déjà
     * donne une nouvelle version de celle-ci (REG-04) : réimporter un fichier corrigé ne crée pas de
     * doublon.
     *
     * @throws RuleEvaluationException si le fichier lui-même n'est pas lisible
     */
    public ImportReport importRules(String content, long userId) {
        List<RuleExport> rules = read(content);
        var created = new ArrayList<String>();
        var updated = new ArrayList<String>();
        var rejected = new ArrayList<Rejection>();
        var knownByName = namesToCodes();
        for (RuleExport rule : rules) {
            try {
                var form = toForm(rule);
                validator.validate(form);
                String existing = knownByName.get(form.name());
                if (existing == null) {
                    String code = catalog.create(form, userId);
                    knownByName.put(form.name(), code);
                    created.add(form.name() + " (" + code + ")");
                } else {
                    int version = catalog.update(existing, form, userId);
                    updated.add(form.name() + " (" + existing + ", version " + version + ")");
                }
            } catch (RuntimeException e) {
                rejected.add(new Rejection(name(rule), e.getMessage()));
            }
        }
        return new ImportReport(created, updated, rejected);
    }

    private List<RuleExport> read(String content) {
        try {
            List<RuleExport> rules = json.readValue(content, RULES);
            if (rules == null || rules.isEmpty()) {
                throw new RuleEvaluationException("Le fichier ne contient aucune règle");
            }
            return rules;
        } catch (RuleEvaluationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RuleEvaluationException(
                    "Le fichier n'est pas un export de règles lisible : il doit contenir une liste de règles en JSON");
        }
    }

    private RuleForm toForm(RuleExport rule) {
        if (rule.name() == null || rule.name().isBlank()) {
            throw new RuleEvaluationException("Une règle du fichier n'a pas de nom");
        }
        if (rule.type() == null || rule.type().isBlank()) {
            throw new RuleEvaluationException("La règle « " + rule.name() + " » n'indique pas de type de contrôle");
        }
        return new RuleForm(
                rule.name(),
                rule.description(),
                rule.type(),
                rule.targetColumns() == null ? List.of() : rule.targetColumns(),
                rule.params() == null ? Map.of() : rule.params(),
                new Normalization(
                        Boolean.TRUE.equals(rule.trim()),
                        Boolean.TRUE.equals(rule.upperCase()),
                        Boolean.TRUE.equals(rule.stripAccents())),
                rule.severity() == null ? Severity.MAJOR : rule.severity(),
                rule.thresholdPercent() == null ? 100 : rule.thresholdPercent(),
                rule.message(),
                rule.businessOwner(),
                "Import d'un fichier de règles");
    }

    /** Nom de règle → code, sur les versions courantes : le nom est ce que l'analyste reconnaît. */
    private Map<String, String> namesToCodes() {
        var byName = new java.util.HashMap<String, String>();
        for (RuleSummary summary : queries.list(RuleFilter.ALL)) {
            byName.putIfAbsent(summary.name(), summary.ruleCode());
        }
        return byName;
    }

    private static String name(RuleExport rule) {
        return rule.name() == null || rule.name().isBlank() ? "(sans nom)" : rule.name();
    }
}
