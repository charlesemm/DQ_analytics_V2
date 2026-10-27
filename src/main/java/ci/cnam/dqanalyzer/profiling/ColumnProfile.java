package ci.cnam.dqanalyzer.profiling;

import java.util.List;

/**
 * Profil d'une colonne (PRF-01 à PRF-03).
 *
 * @param detectedType type majoritaire parmi les cellules remplies : ENTIER, DECIMAL, DATE ou TEXTE
 * @param topValues 10 valeurs les plus fréquentes, avec leur nombre d'apparitions
 * @param patterns motifs dominants : chaque chiffre devient 9, chaque majuscule A, chaque minuscule a
 */
public record ColumnProfile(
        String column,
        long rowCount,
        long filled,
        long distinctValues,
        Integer minLength,
        Integer maxLength,
        String minValue,
        String maxValue,
        String detectedType,
        List<ValueCount> topValues,
        List<ValueCount> patterns) {

    public record ValueCount(String value, long count) {}

    public long empty() {
        return rowCount - filled;
    }

    public double fillRate() {
        return rowCount == 0 ? 0 : filled * 100.0 / rowCount;
    }
}
