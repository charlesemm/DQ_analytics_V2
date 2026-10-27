package ci.cnam.dqanalyzer.profiling.api;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ImportedTable;
import ci.cnam.dqanalyzer.profiling.ColumnProfile;
import ci.cnam.dqanalyzer.profiling.domain.ColumnProfiler;
import ci.cnam.dqanalyzer.profiling.domain.RuleSuggester;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Écran « Profil du fichier » (CDC §5.2, PRF-01 à PRF-04), affiché juste après l'import : l'analyste
 * voit ce que contient chaque colonne avant de choisir ses contrôles.
 *
 * <p>L'écran ne lit et n'écrit rien : il calcule le profil dans la base DuckDB du fichier, à la
 * demande. Le profil n'est pas encore mis en cache (ce sera la colonne {@code source_file.profile}
 * de l'étape suivante) : sur un fichier volumineux, l'affichage recalcule donc tout.
 */
@Controller
class ProfilePageController {

    private final ImportService imports;
    private final DuckDbDatabases databases;
    private final ColumnProfiler profiler;
    private final RuleSuggester suggester;

    ProfilePageController(
            ImportService imports, DuckDbDatabases databases, ColumnProfiler profiler, RuleSuggester suggester) {
        this.imports = imports;
        this.databases = databases;
        this.profiler = profiler;
        this.suggester = suggester;
    }

    /** Un profil ne modifie rien : un lecteur y a droit, comme à un rapport. */
    @GetMapping("/fichiers/{sourceFileId}/profil")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String profile(@PathVariable long sourceFileId, Model model) {
        var table = imports.tableOf(sourceFileId);
        List<ColumnProfile> profiles = profileColumns(table, table.columns());
        model.addAttribute("sourceFileId", sourceFileId);
        model.addAttribute("rowCount", table.rowCount());
        model.addAttribute("profiles", profiles);
        model.addAttribute("suggestions", suggester.suggest(profiles));
        return "profiling/profile";
    }

    /**
     * Détail d'une colonne, déplié par HTMX au clic sur sa ligne : les 10 valeurs les plus fréquentes
     * et les 5 motifs dominants (PRF-02, PRF-03). Seule la colonne demandée est profilée à nouveau,
     * soit 3 requêtes — c'est moins cher que de garder tous les détails dans la page.
     */
    @GetMapping("/fichiers/{sourceFileId}/profil/colonne")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST', 'READER')")
    String columnDetail(@PathVariable long sourceFileId, @RequestParam String column, Model model) {
        var table = imports.tableOf(sourceFileId);
        if (!table.columns().contains(column)) {
            throw new ImportRejectedException("profiling.error.colonne-inconnue");
        }
        model.addAttribute("profile", profileColumns(table, List.of(column)).getFirst());
        return "profiling/fragments/column-detail";
    }

    /**
     * Ouvre la base du fichier le temps du calcul, puis la referme : une base DuckDB ouverte garde un
     * verrou sur le fichier, et un écran n'a aucune raison de le conserver entre deux requêtes.
     */
    private List<ColumnProfile> profileColumns(ImportedTable table, List<String> columns) {
        try (Connection connection = databases.open(table.database())) {
            return profiler.profile(new Dataset(connection, table.tableName(), columns));
        } catch (SQLException e) {
            throw new ImportRejectedException("profiling.error.profil-impossible");
        }
    }
}
