package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.util.NoSuchElementException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduit les refus d'import en réponses lisibles (IMP-05, ERG-02) : le texte vient de
 * messages.properties, la clé portée par l'exception ne sort jamais vers l'utilisateur.
 */
@RestControllerAdvice(assignableTypes = {ImportController.class, UploadController.class})
class ImportErrorHandler {

    private final MessageSource messages;

    ImportErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ImportRejectedException.class)
    ProblemDetail rejected(ImportRejectedException e) {
        String detail = messages.getMessage(e.messageKey(), e.arguments().toArray(), LocaleContextHolder.getLocale());
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, detail);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                messages.getMessage("import.error.inconnu", null, LocaleContextHolder.getLocale()));
    }
}
