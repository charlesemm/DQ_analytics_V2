package ci.cnam.dqanalyzer.rules.api;

import java.util.List;

/**
 * Saisie refusée par le formulaire de règle : le message est une clé de messages.properties, jamais
 * un texte en dur, et il est rédigé pour l'analyste (ERG-02).
 */
class InvalidRuleFormException extends RuntimeException {

    private final transient List<Object> arguments;

    InvalidRuleFormException(String messageKey, Object... arguments) {
        super(messageKey);
        this.arguments = List.of(arguments);
    }

    String messageKey() {
        return getMessage();
    }

    Object[] arguments() {
        return arguments.toArray();
    }
}
