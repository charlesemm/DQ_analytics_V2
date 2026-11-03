package ci.cnam.dqanalyzer.reference;

import java.util.List;

/**
 * Chargement d'une table de référence refusé pour une raison que l'analyste peut corriger. Le
 * message est une clé de messages.properties : la clé ne sort jamais vers l'utilisateur (ERG-02).
 */
public class ReferenceRejectedException extends RuntimeException {

    private final transient List<Object> arguments;

    public ReferenceRejectedException(String messageKey, Object... arguments) {
        super(messageKey);
        this.arguments = List.of(arguments);
    }

    public String messageKey() {
        return getMessage();
    }

    public List<Object> arguments() {
        return arguments;
    }
}
