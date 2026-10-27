package ci.cnam.dqanalyzer.importing;

/**
 * Une colonne d'un fichier à largeur fixe : où elle commence et combien de caractères elle occupe
 * (IMP-08). C'est l'unité du gabarit, que l'analyste saisit une fois et réutilise pour tous les
 * fichiers du même extracteur.
 *
 * @param name nom de la colonne ; vide = à prendre dans la ligne d'en-tête, ou « colonne_n »
 * @param start position du premier caractère dans la ligne, à partir de 1
 * @param length nombre de caractères
 */
public record FixedWidthField(String name, int start, int length) {

    public FixedWidthField {
        if (start < 1) {
            throw new ImportRejectedException("import.error.gabarit-position-invalide");
        }
        if (length < 1) {
            throw new ImportRejectedException("import.error.gabarit-longueur-invalide");
        }
        name = name == null ? "" : name.trim();
    }

    /** Position, à partir de 0, du caractère qui suit la colonne. */
    public int end() {
        return start - 1 + length;
    }
}
