package ci.cnam.dqanalyzer.importing;

import java.time.Instant;

/**
 * Un fichier déjà chargé, tel que les autres écrans le proposent dans une liste : essai d'une règle
 * sur un vrai fichier (REG-03), association des colonnes d'un modèle (MOD-02), lancement d'une
 * analyse.
 */
public record LoadedFile(long id, String originalName, long rowCount, Instant uploadedAt) {}
