package ci.cnam.dqanalyzer.templates.api;

import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleCatalogQueries;
import ci.cnam.dqanalyzer.rules.RuleCatalogQueries.RuleFilter;
import ci.cnam.dqanalyzer.security.CurrentUserProvider;
import ci.cnam.dqanalyzer.templates.TemplateService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Écrans des modèles d'analyse (CDC §5.4, MOD-01 à MOD-03) : composer un modèle (colonnes attendues,
 * règles retenues et ordonnées, poids des dimensions), puis associer ses colonnes à celles d'un
 * fichier importé.
 *
 * <p>La consultation est ouverte au Lecteur ; composer un modèle et enregistrer une correspondance
 * sont réservés aux rôles ADMIN et ANALYST (SEC-02).
 */
@Controller
class TemplateController {

    /** Fichiers proposés pour l'association des colonnes : les plus récents suffisent. */
    private static final int LOADED_FILES_SHOWN = 20;

    private final TemplateService templates;
    private final RuleCatalogQueries rules;
    private final ImportService imports;
    private final CurrentUserProvider currentUser;

    TemplateController(
            TemplateService templates,
            RuleCatalogQueries rules,
            ImportService imports,
            CurrentUserProvider currentUser) {
        this.templates = templates;
        this.rules = rules;
        this.imports = imports;
        this.currentUser = currentUser;
    }

    @GetMapping("/modeles")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String list(Model model) {
        model.addAttribute("templates", templates.list());
        return "templates/list";
    }

    @GetMapping("/modeles/nouveau")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String newTemplate(Model model) {
        return form(new TemplateFormRequest(), null, model);
    }

    @GetMapping("/modeles/{templateId}/modifier")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String editTemplate(@PathVariable long templateId, Model model) {
        return form(TemplateFormRequest.of(templates.byId(templateId)), templateId, model);
    }

    @PostMapping("/modeles")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String create(@ModelAttribute("form") TemplateFormRequest request, Model model) {
        if (isBlank(request.getName())) {
            model.addAttribute("error", "templates.error.nom");
            return form(request, null, model);
        }
        long id = templates.create(request.toTemplateForm(), currentUser.currentUserId());
        return "redirect:/modeles/" + id + "/colonnes";
    }

    @PostMapping("/modeles/{templateId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String update(@PathVariable long templateId, @ModelAttribute("form") TemplateFormRequest request, Model model) {
        if (isBlank(request.getName())) {
            model.addAttribute("error", "templates.error.nom");
            return form(request, templateId, model);
        }
        templates.update(templateId, request.toTemplateForm());
        return "redirect:/modeles";
    }

    /**
     * Association des colonnes attendues avec celles d'un fichier (MOD-02) : la correspondance est
     * proposée par {@code ColumnMatcher}, l'analyste corrige ce qui n'a pas été reconnu.
     */
    @GetMapping("/modeles/{templateId}/colonnes")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String columns(@PathVariable long templateId, @RequestParam(required = false) Long sourceFileId, Model model) {
        var template = templates.byId(templateId);
        var loadedFiles = imports.loadedFiles(LOADED_FILES_SHOWN);
        Long chosen = sourceFileId != null
                ? sourceFileId
                : loadedFiles.stream().findFirst().map(file -> file.id()).orElse(null);
        List<String> fileColumns =
                chosen == null ? List.of() : imports.tableOf(chosen).columns();
        model.addAttribute("template", template);
        model.addAttribute("loadedFiles", loadedFiles);
        model.addAttribute("sourceFileId", chosen);
        model.addAttribute("fileColumns", fileColumns);
        model.addAttribute(
                "mapping",
                chosen == null
                        ? emptyMapping(template.expectedColumns())
                        : templates.proposeMapping(templateId, fileColumns));
        return "templates/columns";
    }

    /** Enregistre la correspondance retenue (MOD-03) : c'est elle qui servira avant l'exécution. */
    @PostMapping("/modeles/{templateId}/colonnes")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String saveColumns(@PathVariable long templateId, @RequestParam Map<String, String> params) {
        var mapping = new LinkedHashMap<String, String>();
        params.forEach((key, value) -> {
            if (key.startsWith("colonne.")) {
                mapping.put(key.substring("colonne.".length()), value);
            }
        });
        templates.saveColumnMapping(templateId, mapping);
        return "redirect:/modeles";
    }

    private String form(TemplateFormRequest request, Long templateId, Model model) {
        model.addAttribute("form", request);
        model.addAttribute("templateId", templateId);
        model.addAttribute("dimensions", Dimension.values());
        model.addAttribute("rules", rules.list(new RuleFilter(null, null, null, true, null)));
        return "templates/form";
    }

    private static Map<String, String> emptyMapping(List<String> expectedColumns) {
        var mapping = new LinkedHashMap<String, String>();
        expectedColumns.forEach(column -> mapping.put(column, null));
        return mapping;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
