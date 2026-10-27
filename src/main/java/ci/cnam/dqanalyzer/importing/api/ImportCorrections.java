package ci.cnam.dqanalyzer.importing.api;

/**
 * Corrections apportées par l'analyste à ce que la détection a proposé (IMP-02, IMP-03, IMP-07, IMP-08).
 * Chaque champ absent veut dire « garder ce qui a été détecté » ; chaque format n'en utilise que
 * les siens.
 *
 * @param delimiter texte délimité : SEMICOLON, COMMA ou TAB
 * @param charset fichier texte : UTF-8 ou windows-1252
 * @param hasHeader présence d'une ligne d'en-tête
 * @param sheet tableur : nom de la feuille à lire
 * @param headerRow tableur : n° de la ligne d'en-tête, à partir de 1
 * @param template largeur fixe : gabarit {@code nom:début:longueur}, colonnes séparées par des
 *     virgules — {@code num_secu:1:13,nom:14:30}
 * @param rowElement XML : nom de l'élément répété
 * @param arrayPath JSON : chemin du tableau d'objets
 */
record ImportCorrections(
        String delimiter,
        String charset,
        Boolean hasHeader,
        String sheet,
        Integer headerRow,
        String template,
        String rowElement,
        String arrayPath) {}
