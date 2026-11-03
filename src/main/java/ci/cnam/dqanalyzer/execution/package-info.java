/**
 * Exécution des analyses (EXE-01, EXE-02) : lancement asynchrone, moteur qui applique les règles d'un
 * modèle à la table DuckDB d'un fichier, enregistrement des résultats et des anomalies, et calcul des
 * scores par règle, dimension, colonne et global (CDC §7).
 */
@ApplicationModule(displayName = "Exécution des analyses")
package ci.cnam.dqanalyzer.execution;

import org.springframework.modulith.ApplicationModule;
