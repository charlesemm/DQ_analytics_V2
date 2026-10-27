/**
 * Profil des colonnes d'un fichier importé : remplissage, valeurs distinctes, longueurs, type
 * probable, valeurs et motifs les plus fréquents (PRF-01 à PRF-03), puis suggestions de règles
 * (PRF-04). Le profil se calcule en SQL, dans la base DuckDB du fichier : rien n'est chargé en
 * mémoire.
 */
@ApplicationModule(displayName = "Profilage des colonnes")
package ci.cnam.dqanalyzer.profiling;

import org.springframework.modulith.ApplicationModule;
