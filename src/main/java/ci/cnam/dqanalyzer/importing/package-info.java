/**
 * Import des fichiers : détection du format, de l'encodage et du séparateur (IMP-07), refus des
 * fichiers inexploitables (IMP-05), conservation du fichier source (IMP-06), puis lecture en flux
 * vers une table DuckDB (PERF-03). L'envoi par morceaux (IMP-01) viendra dans un lot dédié.
 */
@ApplicationModule(displayName = "Import des fichiers")
package ci.cnam.dqanalyzer.importing;

import org.springframework.modulith.ApplicationModule;
