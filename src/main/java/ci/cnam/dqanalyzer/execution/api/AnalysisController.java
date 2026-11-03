package ci.cnam.dqanalyzer.execution.api;

import ci.cnam.dqanalyzer.execution.AnalysisService;
import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.security.CurrentUserProvider;
import ci.cnam.dqanalyzer.templates.TemplateService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Écrans « Lancer une analyse » et « Suivi de l'analyse » (CDC §5.6, EXE-01, EXE-02).
 *
 * <p>Le lancement rend la main tout de suite : l'analyse tourne en tâche de fond et la page de suivi
 * demande l'avancement toutes les 2 secondes. Lancer écrit, donc c'est réservé aux rôles ADMIN et
 * ANALYST ; suivre est une lecture, ouverte au Lecteur (SEC-02).
 */
@Controller
class AnalysisController {

    /** Fichiers et modèles proposés au lancement : les plus récents suffisent. */
    private static final int CHOICES_SHOWN = 20;

    private final AnalysisService analyses;
    private final ImportService imports;
    private final TemplateService templates;
    private final CurrentUserProvider currentUser;

    AnalysisController(
            AnalysisService analyses,
            ImportService imports,
            TemplateService templates,
            CurrentUserProvider currentUser) {
        this.analyses = analyses;
        this.imports = imports;
        this.templates = templates;
        this.currentUser = currentUser;
    }

    @GetMapping("/analyses/nouvelle")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String newAnalysis(
            @RequestParam(required = false) Long sourceFileId,
            @RequestParam(required = false) Long templateId,
            Model model) {
        var loadedFiles = imports.loadedFiles(CHOICES_SHOWN);
        var allTemplates = templates.list();
        model.addAttribute("loadedFiles", loadedFiles);
        model.addAttribute("templates", allTemplates);
        model.addAttribute(
                "sourceFileId",
                sourceFileId != null
                        ? sourceFileId
                        : loadedFiles.stream()
                                .findFirst()
                                .map(file -> file.id())
                                .orElse(null));
        model.addAttribute("templateId", templateId);
        // L'association des colonnes du modèle choisi : une analyse n'a de sens que si elle est faite.
        model.addAttribute(
                "mapping",
                templateId == null
                        ? List.of()
                        : templates.byId(templateId).columnMapping().entrySet());
        return "execution/new";
    }

    @PostMapping("/analyses")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String start(@RequestParam long sourceFileId, @RequestParam long templateId) {
        long runId = analyses.start(sourceFileId, templateId, currentUser.currentUserId());
        return "redirect:/analyses/" + runId + "/suivi";
    }

    @GetMapping("/analyses/{runId}/suivi")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String follow(@PathVariable long runId, Model model) {
        model.addAttribute("runId", runId);
        model.addAttribute("progress", analyses.progress(runId));
        return "execution/follow";
    }

    /**
     * Fragment rechargé par HTMX toutes les 2 secondes. Il cesse de se recharger de lui-même quand
     * l'analyse est terminée : la page n'interroge pas le serveur indéfiniment.
     */
    @GetMapping("/analyses/{runId}/progression")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String progress(@PathVariable long runId, Model model) {
        model.addAttribute("runId", runId);
        model.addAttribute("progress", analyses.progress(runId));
        return "execution/fragments/progress";
    }
}
