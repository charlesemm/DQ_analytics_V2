package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Envoi d'un fichier et suivi de son chargement (IMP-03, IMP-05, IMP-06, IMP-07).
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
     * @param delimiter SEMICOLON, COMMA ou TAB pour corriger la détection ; absent = détection retenue
     * @param charset UTF-8 ou windows-1252 pour corriger la détection
     * @param hasHeader présence d'une ligne d'en-tête ; absent = détection retenue
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ResponseEntity<ImportResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String delimiter,
            @RequestParam(required = false) String charset,
            @RequestParam(required = false) Boolean hasHeader)
            throws IOException {
        var received = imports.receive(file.getInputStream(), file.getOriginalFilename());
        var retained = correct(received.detectedSettings(), delimiter, charset, hasHeader);
        imports.startLoad(received.sourceFileId(), retained);
        return ResponseEntity.accepted()
                .location(URI.create("/api/imports/" + received.sourceFileId()))
                .body(ImportResponse.of(received, retained));
    }

    @GetMapping("/{sourceFileId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    ImportStatusResponse status(@PathVariable long sourceFileId) {
        return ImportStatusResponse.of(imports.progressOf(sourceFileId));
    }

    /** Les choix de l'analyste l'emportent sur la détection automatique (IMP-03). */
    private static ImportSettings correct(
            ImportSettings detected, String delimiter, String charset, Boolean hasHeader) {
        var retained = detected;
        if (delimiter != null && !delimiter.isBlank()) {
            retained = retained.withDelimiter(Delimiters.toCharacter(delimiter));
        }
        if (charset != null && !charset.isBlank()) {
            retained = retained.withCharsetName(charset);
        }
        if (hasHeader != null) {
            retained = retained.withHasHeader(hasHeader);
        }
        return retained;
    }
}
