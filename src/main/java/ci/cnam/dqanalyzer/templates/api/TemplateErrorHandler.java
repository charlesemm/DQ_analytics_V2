package ci.cnam.dqanalyzer.templates.api;

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
 * Les écrans des modèles disent en français ce qui manque, au lieu d'une page d'erreur technique
 * (ERG-02) : un modèle supprimé entre-temps, ou un fichier dont le chargement n'est pas terminé.
 */
@ControllerAdvice(assignableTypes = TemplateController.class)
class TemplateErrorHandler {

    private final MessageSource messages;

    TemplateErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String unknown(Model model) {
        model.addAttribute("message", message("templates.error.inconnu"));
        return "templates/unavailable";
    }

    @ExceptionHandler(ImportRejectedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    String fileUnavailable(ImportRejectedException e, Model model) {
        model.addAttribute(
                "message",
                messages.getMessage(e.messageKey(), e.arguments().toArray(), LocaleContextHolder.getLocale()));
        return "templates/unavailable";
    }

    private String message(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
