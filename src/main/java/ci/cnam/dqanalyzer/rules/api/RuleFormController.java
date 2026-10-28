package ci.cnam.dqanalyzer.rules.api;

import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.RuleCatalogQueries;
import ci.cnam.dqanalyzer.rules.RuleCatalogService;
import ci.cnam.dqanalyzer.rules.RuleDefinitionReader;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluatorRegistry;
import ci.cnam.dqanalyzer.rules.RuleFormValidator;
import ci.cnam.dqanalyzer.rules.RuleTester;
import ci.cnam.dqanalyzer.rules.Severity;
import ci.cnam.dqanalyzer.rules.expression.Condition;
import ci.cnam.dqanalyzer.security.CurrentUserProvider;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

/**
 * Formulaire de règle (CDC §5.3, REG-01, REG-02) : l'analyste choisit un type de contrôle, et les
 * champs de paramètres sont générés à partir du {@code paramSchema()} de l'évaluateur. Aucun HTML
 * n'est à écrire pour un nouveau type de règle.
 *
 * <p>Avant d'enregistrer, la règle est exécutée sur une table vide pour que les erreurs de
 * paramètres apparaissent tout de suite (REG-02). La création et la modification sont réservées aux
 * rôles ADMIN et ANALYST (SEC-02).
 */
@Controller
class RuleFormController {

    /** Fichiers proposés pour un essai : les plus récents suffisent (REG-03). */
    private static final int LOADED_FILES_SHOWN = 20;

    private final RuleEvaluatorRegistry registry;
    private final RuleCatalogService catalog;
    private final RuleCatalogQueries queries;
    private final RuleDefinitionReader reader;
    private final RuleFormValidator validator;
    private final CurrentUserProvider currentUser;
    private final ImportService imports;
    private final ObjectMapper json;
    private final RuleTester tester;
    private final RuleMessages errors;

    RuleFormController(
            RuleEvaluatorRegistry registry,
            RuleCatalogService catalog,
            RuleCatalogQueries queries,
            RuleDefinitionReader reader,
            RuleFormValidator validator,
            CurrentUserProvider currentUser,
            ImportService imports,
            ObjectMapper json,
            RuleTester tester,
            RuleMessages errors) {
        this.registry = registry;
        this.catalog = catalog;
        this.queries = queries;
        this.reader = reader;
        this.validator = validator;
        this.currentUser = currentUser;
        this.imports = imports;
        this.json = json;
        this.tester = tester;
        this.errors = errors;
    }

    @GetMapping("/regles/nouvelle")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String newRule(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long sourceFileId,
            @RequestParam(required = false) String column,
            Model model) {
        var request = new RuleFormRequest();
        request.setType(type);
        if (column != null && !column.isBlank()) {
            request.getTargetColumns().add(column);
        }
        return form(request, null, sourceFileId, model);
    }

    /** Modifier une règle : l'enregistrement créera la version suivante (REG-04). */
    @GetMapping("/regles/{ruleCode}/modifier")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String editRule(@PathVariable String ruleCode, @RequestParam(required = false) Long sourceFileId, Model model) {
        return form(prefilled(ruleCode), ruleCode, sourceFileId, model);
    }

    /** Dupliquer une règle : même saisie, mais enregistrée sous un nouveau code (REG-06). */
    @GetMapping("/regles/{ruleCode}/dupliquer")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String duplicateRule(
            @PathVariable String ruleCode, @RequestParam(required = false) Long sourceFileId, Model model) {
        var request = prefilled(ruleCode);
        request.setName(request.getName() + " (copie)");
        return form(request, null, sourceFileId, model);
    }

    /**
     * Champs de paramètres du type choisi, rechargés par HTMX quand l'analyste change de type : les
     * paramètres d'un CMP-01 et d'un VAL-04 n'ont rien à voir.
     */
    @GetMapping("/regles/formulaire/parametres")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String params(@RequestParam String type, @RequestParam(required = false) Long sourceFileId, Model model) {
        var request = new RuleFormRequest();
        request.setType(type);
        model.addAttribute("form", request);
        addSchema(model, type);
        addChoices(model, sourceFileId);
        return "rules/fragments/params";
    }

    /**
     * Essai de la règle en cours de saisie sur un fichier déjà importé (REG-03), appelé par HTMX :
     * rien n'est enregistré, et seules les 50 premières anomalies sont montrées.
     */
    @PostMapping("/regles/essai")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String test(@ModelAttribute("form") RuleFormRequest request, @RequestParam long testFileId, Model model) {
        try {
            model.addAttribute("result", tester.test(request.toRuleForm(json), testFileId));
        } catch (InvalidRuleFormException | RuleEvaluationException | NoSuchElementException e) {
            model.addAttribute("error", errors.message(e));
        }
        model.addAttribute("maxAnomalies", RuleTester.MAX_ANOMALIES);
        return "rules/fragments/test-result";
    }

    @PostMapping("/regles")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String create(
            @ModelAttribute("form") RuleFormRequest request,
            @RequestParam(required = false) Long sourceFileId,
            Model model) {
        try {
            var rule = request.toRuleForm(json);
            require(rule.name() != null, "rules.form.error.nom");
            require(rule.type() != null && !rule.type().isBlank(), "rules.form.error.type");
            validator.validate(rule);
            catalog.create(rule, currentUser.currentUserId());
            return "redirect:/regles";
        } catch (InvalidRuleFormException | RuleEvaluationException e) {
            return again(request, null, sourceFileId, e, model);
        }
    }

    /** Enregistre une modification : nouvelle version, l'ancienne reste dans l'historique (REG-04). */
    @PostMapping("/regles/{ruleCode}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String update(
            @PathVariable String ruleCode,
            @ModelAttribute("form") RuleFormRequest request,
            @RequestParam(required = false) Long sourceFileId,
            Model model) {
        try {
            var rule = request.toRuleForm(json);
            require(rule.name() != null, "rules.form.error.nom");
            require(rule.changeComment() != null, "rules.form.error.commentaire");
            validator.validate(rule);
            catalog.update(ruleCode, rule, currentUser.currentUserId());
            return "redirect:/regles";
        } catch (InvalidRuleFormException | RuleEvaluationException e) {
            return again(request, ruleCode, sourceFileId, e, model);
        }
    }

    /**
     * Saisie refusée : on réaffiche le formulaire avec ce que l'analyste a saisi et la raison du
     * refus, plutôt qu'une page d'erreur qui lui ferait tout recommencer (ERG-02).
     */
    private String again(
            RuleFormRequest request, String ruleCode, Long sourceFileId, RuntimeException error, Model model) {
        model.addAttribute("error", errors.message(error));
        return form(request, ruleCode, sourceFileId, model);
    }

    private RuleFormRequest prefilled(String ruleCode) {
        var versions = queries.history(ruleCode);
        if (versions.isEmpty()) {
            throw new RuleNotFoundException(ruleCode);
        }
        return RuleFormRequest.of(reader.current(ruleCode), versions.getFirst(), json);
    }

    private String form(RuleFormRequest request, String ruleCode, Long sourceFileId, Model model) {
        model.addAttribute("form", request);
        model.addAttribute("ruleCode", ruleCode);
        addSchema(model, request.getType());
        addChoices(model, sourceFileId);
        return "rules/form";
    }

    private void addSchema(Model model, String type) {
        ParamSchema schema =
                type == null || type.isBlank() ? null : registry.get(type).paramSchema();
        model.addAttribute("schema", schema);
        model.addAttribute(
                "dimension",
                type == null || type.isBlank() ? null : registry.get(type).dimension());
        model.addAttribute("expressionParams", RuleFormRequest.EXPRESSION_PARAMS);
    }

    private void addChoices(Model model, Long sourceFileId) {
        model.addAttribute("types", registry.types());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("operators", Condition.Operator.values());
        model.addAttribute("valueTypes", Condition.ValueType.values());
        model.addAttribute("businessOwners", queries.businessOwners());
        model.addAttribute("referenceTables", queries.referenceTables());
        model.addAttribute("sourceFileId", sourceFileId);
        model.addAttribute("fileColumns", fileColumns(sourceFileId));
        model.addAttribute("loadedFiles", imports.loadedFiles(LOADED_FILES_SHOWN));
    }

    /**
     * Colonnes du fichier déjà importé, quand l'analyste crée la règle depuis son profil : il choisit
     * alors dans une liste au lieu de saisir un nom de colonne (ERG-01). Sans fichier, la saisie
     * reste libre, et le nom sera vérifié à l'exécution.
     */
    private List<String> fileColumns(Long sourceFileId) {
        if (sourceFileId == null) {
            return List.of();
        }
        try {
            return imports.tableOf(sourceFileId).columns();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static void require(boolean condition, String messageKey) {
        if (!condition) {
            throw new InvalidRuleFormException(messageKey);
        }
    }
}
