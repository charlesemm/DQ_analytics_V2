package ci.cnam.dqanalyzer.rules.api;

import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleCatalogQueries;
import ci.cnam.dqanalyzer.rules.RuleCatalogQueries.RuleFilter;
import ci.cnam.dqanalyzer.rules.RuleCatalogService;
import ci.cnam.dqanalyzer.rules.RuleEvaluatorRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Écran « Règles de qualité » (CDC §5.3, REG-06) : la liste filtrable du catalogue, l'historique des
 * versions d'une règle (REG-04) et l'activation ou la désactivation d'une règle.
 *
 * <p>La liste ne modifie rien : un lecteur y a droit. Tout ce qui écrit est réservé aux rôles
 * ADMIN et ANALYST (SEC-02).
 */
@Controller
class RuleListController {

    private final RuleCatalogQueries queries;
    private final RuleCatalogService catalog;
    private final RuleEvaluatorRegistry registry;

    RuleListController(RuleCatalogQueries queries, RuleCatalogService catalog, RuleEvaluatorRegistry registry) {
        this.queries = queries;
        this.catalog = catalog;
        this.registry = registry;
    }

    @GetMapping("/regles")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String list(
            @RequestParam(required = false) Dimension dimension,
            @RequestParam(required = false) String businessOwner,
            @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Long templateId,
            Model model) {
        var filter = new RuleFilter(dimension, businessOwner, authorId, active, templateId);
        model.addAttribute("rules", queries.list(filter));
        model.addAttribute("filter", filter);
        model.addAttribute("dimensions", Dimension.values());
        model.addAttribute("businessOwners", queries.businessOwners());
        model.addAttribute("authors", queries.authors());
        model.addAttribute("types", registry.types());
        return "rules/list";
    }

    /** Historique des versions d'une règle : ce qui a changé, par qui et quand (REG-04, TRA-02). */
    @GetMapping("/regles/{ruleCode}/historique")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String history(@PathVariable String ruleCode, Model model) {
        var versions = queries.history(ruleCode);
        if (versions.isEmpty()) {
            throw new RuleNotFoundException(ruleCode);
        }
        model.addAttribute("ruleCode", ruleCode);
        model.addAttribute("versions", versions);
        return "rules/history";
    }

    /**
     * Activer ou désactiver une règle (REG-06). Ce n'est pas une modification : aucune version n'est
     * créée, la règle n'est simplement plus exécutée par les analyses suivantes.
     */
    @PostMapping("/regles/{ruleCode}/activation")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String setActive(@PathVariable String ruleCode, @RequestParam boolean active) {
        catalog.setActive(ruleCode, active);
        return "redirect:/regles";
    }
}
