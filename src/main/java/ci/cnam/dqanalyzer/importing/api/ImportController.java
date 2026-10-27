package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ImportStatus;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Envoi d'un fichier et suivi de son chargement (IMP-02, IMP-03, IMP-05, IMP-06, IMP-07, IMP-08).
 *
 * <p>L'envoi rend la main dès que le fichier est écrit et contrôlé : la lecture vers DuckDB se
 * poursuit en tâche de fond, le client suit l'avancement sur {@code GET /api/imports/{id}}.
 *
 * <p>L'envoi par morceaux avec reprise (IMP-01) n'est pas encore là : la taille d'un envoi reste
 * donc limitée par {@code spring.servlet.multipart.max-file-size}.
 */
@RestController
@RequestMapping("/api/imports")
class ImportController {

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    /**
     * @param corrections ce que l'analyste corrige de la détection ; tout est facultatif
     * @param load faux pour s'arrêter après la réception, le temps de regarder l'aperçu (IMP-04) et
     *     de corriger les paramètres ; le chargement est alors lancé par {@code POST /{id}/load}
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ResponseEntity<ImportResponse> upload(
            @RequestPart("file") MultipartFile file,
            @ModelAttribute ImportCorrections corrections,
            @RequestParam(defaultValue = "true") boolean load)
            throws IOException {
        var received = imports.receive(file.getInputStream(), file.getOriginalFilename());
        var retained = ImportCorrector.correct(received.detectedSettings(), corrections);
        if (load) {
            imports.startLoad(received.sourceFileId(), retained);
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/imports/" + received.sourceFileId()))
                .body(ImportResponse.of(received, retained, load ? ImportStatus.RUNNING : ImportStatus.PENDING));
    }

    /**
     * Les {@value ci.cnam.dqanalyzer.importing.ImportPreview#ROWS} premières lignes telles que les
     * paramètres donnés les donneraient (IMP-04). Rien n'est chargé ni enregistré : l'analyste peut
     * demander autant d'aperçus qu'il veut en changeant de séparateur, de feuille ou de gabarit.
     */
    @GetMapping("/{sourceFileId}/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    PreviewResponse preview(@PathVariable long sourceFileId, @ModelAttribute ImportCorrections corrections) {
        var retained = ImportCorrector.correct(imports.settingsOf(sourceFileId), corrections);
        return PreviewResponse.of(
                retained, imports.sheetNamesOf(sourceFileId), imports.preview(sourceFileId, retained));
    }

    /** Lance le chargement d'un fichier déjà reçu, avec les paramètres que l'analyste a validés. */
    @PostMapping("/{sourceFileId}/load")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ResponseEntity<ImportStatusResponse> load(
            @PathVariable long sourceFileId, @ModelAttribute ImportCorrections corrections) {
        var retained = ImportCorrector.correct(imports.settingsOf(sourceFileId), corrections);
        imports.startLoad(sourceFileId, retained);
        return ResponseEntity.accepted()
                .location(URI.create("/api/imports/" + sourceFileId))
                .body(ImportStatusResponse.of(imports.progressOf(sourceFileId)));
    }

    @GetMapping("/{sourceFileId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    ImportStatusResponse status(@PathVariable long sourceFileId) {
        return ImportStatusResponse.of(imports.progressOf(sourceFileId));
    }
}
