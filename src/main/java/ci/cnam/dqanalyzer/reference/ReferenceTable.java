package ci.cnam.dqanalyzer.reference;

import java.time.Instant;
import java.util.List;

/**
 * Une version d'une table de référence (REF-01, REF-02). Le nom reste stable d'une version à
 * l'autre ; une nouvelle version ne remplace jamais la précédente, qui reste consultable et
 * référencée par les analyses déjà faites (TRA-02).
 */
public record ReferenceTable(
        long id, String name, int version, List<String> columns, long rowCount, String loadedBy, Instant loadedAt) {

    public ReferenceTable {
        columns = List.copyOf(columns);
    }
}
