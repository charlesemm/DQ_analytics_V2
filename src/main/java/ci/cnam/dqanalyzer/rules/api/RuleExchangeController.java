package ci.cnam.dqanalyzer.rules.api;

import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleCatalogQueries;
import ci.cnam.dqanalyzer.rules.RuleCatalogQueries.RuleFilter;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleExchange;
import ci.cnam.dqanalyzer.security.CurrentUserProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Export et import JSON des règles (CDC §5.3, REG-05) : porter un jeu de règles d'un environnement à
 * un autre sans ressaisie.
 *
 * <p>L'export est une lecture : un Lecteur y a droit. L'import écrit des règles : ADMIN et ANALYST
 * seulement (SEC-02).
 */
@Controller
class RuleExchangeController {

    private static final long MAX_IMPORT_BYTES = 2 * 1024 * 1024;

    private final RuleExchange exchange;
    private final RuleCatalogQueries queries;
    private final CurrentUserProvider currentUser;
    private final RuleMessages messages;

    RuleExchangeController(
            RuleExchange exchange, RuleCatalogQueries queries, CurrentUserProvider currentUser, RuleMessages messages) {
        this.exchange = exchange;
        this.queries = queries;
        this.currentUser = currentUser;
        this.messages = messages;
    }

    /** Écran d'échange : le lien d'export des règles filtrées, et le dépôt d'un fichier à importer. */
    @GetMapping("/regles/echange")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String exchangePage(Model model) {
        model.addAttribute("dimensions", Dimension.values());
        model.addAttribute("businessOwners", queries.businessOwners());
        model.addAttribute("ruleCount", queries.list(RuleFilter.ALL).size());
        return "rules/exchange";
    }

    @GetMapping("/regles/export")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    ResponseEntity<Resource> export(
            @RequestParam(required = false) Dimension dimension,
            @RequestParam(required = false) String businessOwner,
            @RequestParam(required = false) Boolean active) {
        String content = exchange.toJson(exchange.export(new RuleFilter(dimension, businessOwner, null, active, null)));
        var body = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
        String fileName = "regles-dq-" + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.contentLength())
                .body(body);
    }

    @PostMapping("/regles/import")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String importRules(@RequestParam MultipartFile file, Model model) {
        try {
            model.addAttribute("report", exchange.importRules(read(file), currentUser.currentUserId()));
        } catch (InvalidRuleFormException | RuleEvaluationException e) {
            model.addAttribute("error", messages.message(e));
        }
        return "rules/import-report";
    }

    /** Le fichier est lu en entier, mais il est borné : un export de règles tient en quelques kilo-octets. */
    private static String read(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidRuleFormException("rules.echange.error.vide");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new InvalidRuleFormException("rules.echange.error.trop-gros");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidRuleFormException("rules.echange.error.illisible");
        }
    }
}
