package ci.cnam.dqanalyzer.rules;

/**
 * Une cellule en anomalie.
 *
 * @param rowNum numéro de ligne dans le fichier d'origine (tel que l'analyste le voit dans Excel)
 * @param value valeur d'origine, non normalisée
 * @param duplicateGroup identifiant du groupe de doublons, ou null hors règles d'unicité
 */
public record Anomaly(long rowNum, String column, String value, String message, Long duplicateGroup) {}
