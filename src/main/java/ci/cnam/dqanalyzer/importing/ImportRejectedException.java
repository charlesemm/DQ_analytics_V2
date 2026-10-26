package ci.cnam.dqanalyzer.importing;

import java.util.Arrays;
import java.util.List;

/**
 * Fichier refusé par l'import (IMP-05). Le texte montré à l'analyste n'est pas dans le code : cette
 * exception porte la clé du message (messages.properties) et ses arguments, que la couche web résout
 * dans la langue de l'utilisateur.
 *
 * <p>Les arguments ne contiennent jamais de donnée personnelle (aucune valeur de cellule) : un nom de
 * fichier, un numéro de ligne ou une taille, rien de plus.
 */
public class ImportRejectedException extends RuntimeException {

    private final String messageKey;
    private final List<Object> arguments;

    public ImportRejectedException(String messageKey, Object... arguments) {
        super(messageKey);
        this.messageKey = messageKey;
        this.arguments = List.copyOf(Arrays.asList(arguments));
    }

    public String messageKey() {
        return messageKey;
    }

    public List<Object> arguments() {
        return arguments;
    }
}
