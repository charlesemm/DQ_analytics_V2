/**
 * Tables de référence versionnées (REF-01 à REF-03) : communes, nomenclatures, listes de codes. Elles
 * vivent dans PostgreSQL, où elles sont datées, versionnées et sauvegardées — elles survivent donc à
 * la purge des fichiers à 90 jours (SEC-04). Le temps d'une analyse, elles sont copiées dans la base
 * DuckDB du fichier, où les règles EXA-01 et INT-01 les interrogent par jointure.
 */
@ApplicationModule(displayName = "Tables de référence")
package ci.cnam.dqanalyzer.reference;

import org.springframework.modulith.ApplicationModule;
