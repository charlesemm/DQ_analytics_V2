package ci.cnam.dqanalyzer.reference.api;

import ci.cnam.dqanalyzer.reference.ReferenceRejectedException;
import ci.cnam.dqanalyzer.reference.ReferenceTableService;
import ci.cnam.dqanalyzer.security.CurrentUserProvider;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Écran « Tables de référence » (CDC §5.5, REF-01, REF-02) : les référentiels chargés, l'historique
 * de leurs versions et un aperçu de leurs lignes.
 *
 * <p>La consultation est ouverte au Lecteur ; charger une version est réservé aux rôles ADMIN et
 * ANALYST (SEC-02).
 */
@Controller
class ReferenceController {

    private final ReferenceTableService references;
    private final CurrentUserProvider currentUser;

    ReferenceController(ReferenceTableService references, CurrentUserProvider currentUser) {
        this.references = references;
        this.currentUser = currentUser;
    }

    @GetMapping("/references")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String list(Model model) {
        model.addAttribute("references", references.currentVersions());
        return "reference/list";
    }

    /** Historique des versions d'un référentiel : aucune n'est jamais remplacée (REF-02). */
    @GetMapping("/references/{name}/versions")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String versions(@PathVariable String name, Model model) {
        var versions = references.versions(name);
        if (versions.isEmpty()) {
            throw new ReferenceRejectedException("reference.error.inconnue");
        }
        model.addAttribute("name", name);
        model.addAttribute("versions", versions);
        return "reference/versions";
    }

    /** Aperçu des {@value ReferenceTableService#PREVIEW_ROWS} premières lignes d'une version. */
    @GetMapping("/references/versions/{referenceTableId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String preview(@PathVariable long referenceTableId, Model model) {
        model.addAttribute("reference", references.byId(referenceTableId));
        model.addAttribute("rows", references.preview(referenceTableId));
        model.addAttribute("previewRows", ReferenceTableService.PREVIEW_ROWS);
        return "reference/preview";
    }

    @PostMapping("/references")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String load(@RequestParam String name, @RequestParam MultipartFile file, Model model) {
        if (file.isEmpty()) {
            throw new ReferenceRejectedException("reference.error.fichier-obligatoire");
        }
        try (var content = file.getInputStream()) {
            var loaded = references.load(name, content, file.getOriginalFilename(), currentUser.currentUserId());
            return "redirect:/references/versions/" + loaded.id();
        } catch (IOException e) {
            throw new ReferenceRejectedException("reference.error.fichier-illisible");
        }
    }
}
