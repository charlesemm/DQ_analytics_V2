package ci.cnam.dqanalyzer.rules.api;

import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Les écrans des règles affichent un message en français plutôt qu'une page d'erreur technique
 * (ERG-02). Les messages de {@link RuleEvaluationException} sont déjà rédigés pour l'analyste.
 */
@ControllerAdvice(assignableTypes = RuleListController.class)
class RuleErrorHandler {

    private final MessageSource messages;

    RuleErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(RuleNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String unknown(Model model) {
        model.addAttribute("message", message("rules.error.inconnue"));
        return "rules/unavailable";
    }

    @ExceptionHandler(RuleEvaluationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    String rejected(RuleEvaluationException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "rules/unavailable";
    }

    private String message(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
