package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.DelimitedSettings;
import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportPreview;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Écran « Importer un fichier » (IMP-01 à IMP-08). La page est servie une fois ; l'envoi par
 * morceaux, l'aperçu et le suivi du chargement se font ensuite sans la recharger, par les mêmes
 * points d'entrée que l'API.
 *
 * <p>Tout ce que la page doit afficher comme listes de choix vient d'ici : formats acceptés,
 * séparateurs et encodages du CDC. Rien n'est écrit en dur dans le gabarit.
 */
@Controller
class ImportPageController {

    @GetMapping("/imports/nouveau")
    @PreAuthorize("hasAnyRole('ADMIN', 'ANALYST')")
    String newImport(Model model) {
        model.addAttribute("acceptedExtensions", acceptedExtensions());
        model.addAttribute("charsets", DelimitedSettings.SUPPORTED_CHARSETS);
        model.addAttribute("delimiters", Delimiters.names());
        model.addAttribute("sheetFormats", SheetSettings.SHEET_FORMATS);
        model.addAttribute("previewRows", ImportPreview.ROWS);
        return "importing/import";
    }

    /** Pour l'attribut {@code accept} du sélecteur de fichier : « .csv,.tsv,… ». */
    private static String acceptedExtensions() {
        var accepted = new StringBuilder();
        for (ImportFormat format : ImportFormat.values()) {
            for (String extension : format.extensions()) {
                accepted.append(accepted.isEmpty() ? "" : ",").append('.').append(extension);
            }
        }
        return accepted.toString();
    }
}
