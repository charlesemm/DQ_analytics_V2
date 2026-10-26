package ci.cnam.dqanalyzer.rules;

/** Traduit les formats de date saisis par l'analyste (jj/mm/aaaa) en formats DuckDB (%d/%m/%Y). */
public final class DateFormats {

    public static final String DEFAULT = "jj/mm/aaaa";

    private DateFormats() {}

    /** « jj/mm/aaaa » → « %d/%m/%Y ». Un format déjà écrit avec des % est laissé tel quel. */
    public static String toDuckDb(String format) {
        if (format.contains("%")) {
            return format;
        }
        return format.toLowerCase()
                .replace("aaaa", "%Y")
                .replace("jj", "%d")
                .replace("mm", "%m")
                .replace("hh", "%H")
                .replace("mi", "%M")
                .replace("ss", "%S");
    }
}
