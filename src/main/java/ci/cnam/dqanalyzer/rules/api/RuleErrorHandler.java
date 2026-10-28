package ci.cnam.dqanalyzer.rules.api;

import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Les écrans des règles affichent un message en français plutôt qu'une page d'erreur technique
 * (ERG-02). Les messages de {@link RuleEvaluationException} sont déjà rédigés pour l'analyste.
 */
@ControllerAdvice(assignableTypes = {RuleListController.class, RuleFormController.class})
class RuleErrorHandler {

    private final RuleMessages messages;

    RuleErrorHandler(RuleMessages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(RuleNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String unknown(Model model) {
        model.addAttribute("message", messages.message("rules.error.inconnue"));
        return "rules/unavailable";
    }

    @ExceptionHandler(RuleEvaluationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    String rejected(RuleEvaluationException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "rules/unavailable";
    }

    @ExceptionHandler(InvalidRuleFormException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    String invalid(InvalidRuleFormException e, Model model) {
        model.addAttribute("message", messages.message(e));
        return "rules/unavailable";
    }
}
