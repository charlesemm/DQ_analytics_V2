package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ChunkedUpload;
import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ImportStatus;
import ci.cnam.dqanalyzer.importing.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Envoi d'un fichier par morceaux, avec reprise après une coupure réseau (IMP-01, PERF-02).
 *
 * <p>Le parcours : ouvrir l'envoi, pousser les morceaux, demander lesquels sont arrivés si la
 * connexion a lâché, puis assembler. L'assemblage rend la même réponse qu'un envoi direct, et le
 * fichier suit ensuite le chemin habituel : aperçu (IMP-04) puis chargement.
 */
@RestController
@RequestMapping("/api/uploads")
class UploadController {

    private final UploadService uploads;
    private final ImportService imports;

    UploadController(UploadService uploads, ImportService imports) {
        this.uploads = uploads;
        this.imports = imports;
    }

    /**
     * @param totalSize taille annoncée par le navigateur, indicative
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ResponseEntity<ChunkedUpload> start(
            @RequestParam String fileName, @RequestParam(defaultValue = "0") long totalSize) {
        var upload = uploads.start(fileName, totalSize);
        return ResponseEntity.created(URI.create("/api/uploads/" + upload.uploadId()))
                .body(upload);
    }

    /**
     * Pousse un morceau, envoyé tel quel dans le corps de la requête. Renvoyer un morceau déjà reçu
     * est sans conséquence : c'est ce qui permet de reprendre sans réfléchir après une coupure.
     *
     * @param chunkNumber numéro du morceau, à partir de 0
     */
    @PutMapping("/{uploadId}/chunks/{chunkNumber}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ChunkedUpload chunk(@PathVariable long uploadId, @PathVariable int chunkNumber, HttpServletRequest request)
            throws IOException {
        // Le morceau est lu en flux vers le disque : il ne passe jamais entier en mémoire (PERF-03).
        return uploads.receiveChunk(uploadId, chunkNumber, request.getInputStream());
    }

    /** Morceaux déjà reçus : ce que le navigateur demande pour savoir quoi renvoyer. */
    @GetMapping("/{uploadId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ChunkedUpload progress(@PathVariable long uploadId) {
        return uploads.progressOf(uploadId);
    }

    /**
     * Assemble les morceaux et remet le fichier à l'import.
     *
     * @param load faux pour s'arrêter après la réception, le temps de regarder l'aperçu (IMP-04)
     */
    @PostMapping("/{uploadId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ResponseEntity<ImportResponse> complete(
            @PathVariable long uploadId,
            @ModelAttribute ImportCorrections corrections,
            @RequestParam(defaultValue = "false") boolean load) {
        var received = uploads.complete(uploadId);
        var retained = ImportCorrector.correct(received.detectedSettings(), corrections);
        if (load) {
            imports.startLoad(received.sourceFileId(), retained);
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/imports/" + received.sourceFileId()))
                .body(ImportResponse.of(received, retained, load ? ImportStatus.RUNNING : ImportStatus.PENDING));
    }
}
