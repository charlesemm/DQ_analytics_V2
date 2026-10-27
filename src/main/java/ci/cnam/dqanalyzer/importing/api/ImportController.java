package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.DelimitedSettings;
import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.ParquetSettings;
import ci.cnam.dqanalyzer.importing.SheetSettings;
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
 * Envoi d'un fichier et suivi de son chargement (IMP-02, IMP-03, IMP-05, IMP-06, IMP-07).
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
     * @param delimiter fichier texte : SEMICOLON, COMMA ou TAB pour corriger la détection
     * @param charset fichier texte : UTF-8 ou windows-1252 pour corriger la détection
     * @param hasHeader présence d'une ligne d'en-tête ; absent = détection retenue
     * @param sheet tableur : nom de la feuille à lire ; absent = la première
     * @param headerRow tableur : n° de la ligne d'en-tête dans la feuille, à partir de 1
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ResponseEntity<ImportResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String delimiter,
            @RequestParam(required = false) String charset,
            @RequestParam(required = false) Boolean hasHeader,
            @RequestParam(required = false) String sheet,
            @RequestParam(required = false) Integer headerRow)
            throws IOException {
        var received = imports.receive(file.getInputStream(), file.getOriginalFilename());
        var retained = correct(received.detectedSettings(), delimiter, charset, hasHeader, sheet, headerRow);
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

    /** Les choix de l'analyste l'emportent sur la détection automatique (IMP-02, IMP-03). */
    private static ImportSettings correct(
            ImportSettings detected,
            String delimiter,
            String charset,
            Boolean hasHeader,
            String sheet,
            Integer headerRow) {
        return switch (detected) {
            case DelimitedSettings delimited -> correctDelimited(delimited, delimiter, charset, hasHeader);
            case SheetSettings sheetSettings -> correctSheet(sheetSettings, sheet, headerRow, hasHeader);
            // Un fichier Parquet porte son schéma : il n'y a rien à corriger.
            case ParquetSettings parquet -> parquet;
        };
    }

    private static DelimitedSettings correctDelimited(
            DelimitedSettings detected, String delimiter, String charset, Boolean hasHeader) {
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

    /** Pour un tableur, « pas d'en-tête » et « en-tête ligne n » sont le même réglage (IMP-02). */
    private static SheetSettings correctSheet(
            SheetSettings detected, String sheet, Integer headerRow, Boolean hasHeader) {
        var retained = detected;
        if (sheet != null && !sheet.isBlank()) {
            retained = retained.withSheetName(sheet);
        }
        if (headerRow != null) {
            retained = retained.withHeaderRow(headerRow);
        }
        if (Boolean.FALSE.equals(hasHeader)) {
            retained = retained.withHeaderRow(SheetSettings.NO_HEADER);
        } else if (Boolean.TRUE.equals(hasHeader) && !retained.hasHeader()) {
            retained = retained.withHeaderRow(1);
        }
        return retained;
    }
}
