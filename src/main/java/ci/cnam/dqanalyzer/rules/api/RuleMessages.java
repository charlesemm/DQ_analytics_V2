package ci.cnam.dqanalyzer.rules.api;

import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Transforme un refus en phrase française. Les messages de {@link RuleEvaluationException} sont déjà
 * rédigés pour l'analyste ; ceux du formulaire sont des clés de messages.properties, qui ne doivent
 * jamais arriver telles quelles à l'écran.
 */
@Component
class RuleMessages {

    private final MessageSource messages;

    RuleMessages(MessageSource messages) {
        this.messages = messages;
    }

    String message(RuntimeException error) {
        if (error instanceof InvalidRuleFormException invalid) {
            return messages.getMessage(invalid.messageKey(), invalid.arguments(), LocaleContextHolder.getLocale());
        }
        return error.getMessage();
    }

    String message(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
