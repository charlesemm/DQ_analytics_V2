package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.DelimitedSettings;
import ci.cnam.dqanalyzer.importing.FixedWidthSettings;
import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.JsonSettings;
import ci.cnam.dqanalyzer.importing.ParquetSettings;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import ci.cnam.dqanalyzer.importing.XmlSettings;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    ResponseEntity<ImportResponse> upload(
            @RequestPart("file") MultipartFile file, @ModelAttribute ImportCorrections corrections) throws IOException {
        var received = imports.receive(file.getInputStream(), file.getOriginalFilename());
        var retained = correct(received.detectedSettings(), corrections);
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

    /** Les choix de l'analyste l'emportent sur la détection automatique (IMP-02, IMP-03, IMP-08). */
    private static ImportSettings correct(ImportSettings detected, ImportCorrections corrections) {
        return switch (detected) {
            case DelimitedSettings delimited -> correctDelimited(delimited, corrections);
            case SheetSettings sheet -> correctSheet(sheet, corrections);
            case FixedWidthSettings fixedWidth -> correctFixedWidth(fixedWidth, corrections);
            case XmlSettings xml ->
                given(corrections.rowElement()) ? xml.withRowElement(corrections.rowElement()) : xml;
            case JsonSettings json ->
                given(corrections.arrayPath()) ? json.withArrayPath(corrections.arrayPath()) : json;
            // Un fichier Parquet porte son schéma : il n'y a rien à corriger.
            case ParquetSettings parquet -> parquet;
        };
    }

    private static DelimitedSettings correctDelimited(DelimitedSettings detected, ImportCorrections corrections) {
        var retained = detected;
        if (given(corrections.delimiter())) {
            retained = retained.withDelimiter(Delimiters.toCharacter(corrections.delimiter()));
        }
        if (given(corrections.charset())) {
            retained = retained.withCharsetName(corrections.charset());
        }
        if (corrections.hasHeader() != null) {
            retained = retained.withHasHeader(corrections.hasHeader());
        }
        return retained;
    }

    /** Pour un tableur, « pas d'en-tête » et « en-tête ligne n » sont le même réglage (IMP-02). */
    private static SheetSettings correctSheet(SheetSettings detected, ImportCorrections corrections) {
        var retained = detected;
        if (given(corrections.sheet())) {
            retained = retained.withSheetName(corrections.sheet());
        }
        if (corrections.headerRow() != null) {
            retained = retained.withHeaderRow(corrections.headerRow());
        }
        if (Boolean.FALSE.equals(corrections.hasHeader())) {
            retained = retained.withHeaderRow(SheetSettings.NO_HEADER);
        } else if (Boolean.TRUE.equals(corrections.hasHeader()) && !retained.hasHeader()) {
            retained = retained.withHeaderRow(1);
        }
        return retained;
    }

    private static FixedWidthSettings correctFixedWidth(FixedWidthSettings detected, ImportCorrections corrections) {
        var retained = detected;
        if (given(corrections.template())) {
            retained = retained.withFields(FixedWidthTemplates.parse(corrections.template()));
        }
        if (given(corrections.charset())) {
            retained = retained.withCharsetName(corrections.charset());
        }
        if (corrections.hasHeader() != null) {
            retained = retained.withHasHeader(corrections.hasHeader());
        }
        return retained;
    }

    private static boolean given(String value) {
        return value != null && !value.isBlank();
    }
}
