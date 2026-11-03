package ci.cnam.dqanalyzer.execution.api;

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
 * Les écrans d'analyse disent en français ce qui manque (ERG-02) : une analyse ou un modèle demandé
 * à la main dans l'adresse, ou un fichier dont le chargement n'est pas terminé.
 */
@ControllerAdvice(assignableTypes = AnalysisController.class)
class AnalysisErrorHandler {

    private final MessageSource messages;

    AnalysisErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String unknown(Model model) {
        model.addAttribute(
                "message", messages.getMessage("execution.error.inconnue", null, LocaleContextHolder.getLocale()));
        return "execution/unavailable";
    }

    @ExceptionHandler(ImportRejectedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    String unavailable(ImportRejectedException e, Model model) {
        model.addAttribute(
                "message",
                messages.getMessage(e.messageKey(), e.arguments().toArray(), LocaleContextHolder.getLocale()));
        return "execution/unavailable";
    }
}
