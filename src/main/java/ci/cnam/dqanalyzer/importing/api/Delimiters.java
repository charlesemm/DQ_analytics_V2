package ci.cnam.dqanalyzer.importing.api;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import java.util.List;

/**
 * Noms des séparateurs échangés avec le navigateur : un formulaire ne transporte pas une tabulation
 * de façon lisible, et on ne veut pas non plus accepter un caractère quelconque (IMP-03).
 */
final class Delimiters {

    private static final String SEMICOLON = "SEMICOLON";
    private static final String COMMA = "COMMA";
    private static final String TAB = "TAB";

    private Delimiters() {}

    /** Les trois séparateurs du CDC, pour la liste de choix de l'écran d'import (IMP-03). */
    static List<String> names() {
        return List.of(SEMICOLON, COMMA, TAB);
    }

    static char toCharacter(String name) {
        return switch (name) {
            case SEMICOLON -> ';';
            case COMMA -> ',';
            case TAB -> '\t';
            default -> throw new ImportRejectedException("import.error.separateur-inconnu");
        };
    }

    static String toName(char delimiter) {
        return switch (delimiter) {
            case ';' -> SEMICOLON;
            case ',' -> COMMA;
            case '\t' -> TAB;
            default -> throw new ImportRejectedException("import.error.separateur-inconnu");
        };
    }
}
