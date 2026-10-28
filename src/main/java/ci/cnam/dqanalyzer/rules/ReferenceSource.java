package ci.cnam.dqanalyzer.rules;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;

/**
 * Met les tables de référence dont une règle a besoin à portée de son SQL (EXA-01, INT-01).
 *
 * <p>Déclaré ici, et implémenté par le module {@code reference}, pour que les règles n'aient pas à
 * connaître ce module : elles n'en connaissent que ce contrat.
 */
public interface ReferenceSource {

    /**
     * Copie les versions demandées dans la base DuckDB de l'analyse et renvoie de quoi retrouver leur
     * table.
     *
     * @param referenceTableIds identifiants des versions citées par les règles ; liste vide quand
     *     aucune règle n'utilise de référence
     */
    ReferenceProvider loadInto(Connection duck, Collection<Long> referenceTableIds) throws SQLException;

    /** Aucune référence disponible : toute règle qui en demande une reçoit un message clair. */
    ReferenceSource NONE = (duck, referenceTableIds) -> ReferenceProvider.NONE;
}
