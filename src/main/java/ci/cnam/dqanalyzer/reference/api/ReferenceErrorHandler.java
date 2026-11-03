package ci.cnam.dqanalyzer.reference.api;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.reference.ReferenceRejectedException;
import java.util.NoSuchElementException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Un fichier de référence refusé n'est pas une panne : l'écran dit en français ce qui ne va pas
 * (ERG-02). Les refus de lecture viennent du module d'import et portent déjà leur message.
 */
@ControllerAdvice(assignableTypes = ReferenceController.class)
class ReferenceErrorHandler {

    private final MessageSource messages;

    ReferenceErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ReferenceRejectedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    String rejected(ReferenceRejectedException e, Model model) {
        model.addAttribute("message", message(e.messageKey(), e.arguments().toArray()));
        return "reference/unavailable";
    }

    @ExceptionHandler(ImportRejectedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    String unreadable(ImportRejectedException e, Model model) {
        model.addAttribute("message", message(e.messageKey(), e.arguments().toArray()));
        return "reference/unavailable";
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String unknown(Model model) {
        model.addAttribute("message", message("reference.error.inconnue", new Object[0]));
        return "reference/unavailable";
    }

    private String message(String key, Object[] arguments) {
        return messages.getMessage(key, arguments, LocaleContextHolder.getLocale());
    }
}
