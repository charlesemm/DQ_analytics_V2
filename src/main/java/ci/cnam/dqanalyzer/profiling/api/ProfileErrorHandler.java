package ci.cnam.dqanalyzer.profiling.api;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.util.NoSuchElementException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Un profil indisponible n'est pas une erreur technique : le plus souvent, le chargement du fichier
 * n'est pas terminé (IMP-06). L'écran le dit en français (ERG-02) au lieu d'une page d'erreur, et la
 * clé du message ne sort jamais vers l'utilisateur.
 */
@ControllerAdvice(assignableTypes = ProfilePageController.class)
class ProfileErrorHandler {

    private final MessageSource messages;

    ProfileErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ImportRejectedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    String unavailable(ImportRejectedException e, Model model) {
        model.addAttribute(
                "message",
                messages.getMessage(e.messageKey(), e.arguments().toArray(), LocaleContextHolder.getLocale()));
        return "profiling/unavailable";
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String unknown(Model model) {
        model.addAttribute(
                "message", messages.getMessage("import.error.inconnu", null, LocaleContextHolder.getLocale()));
        return "profiling/unavailable";
    }
}
