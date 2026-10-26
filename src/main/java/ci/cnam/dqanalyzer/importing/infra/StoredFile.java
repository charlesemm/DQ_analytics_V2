package ci.cnam.dqanalyzer.importing.infra;

import java.nio.file.Path;

/**
 * Copie du fichier reçu, posée sur le disque (IMP-06).
 *
 * @param path emplacement de la copie, sous un nom tiré au hasard
 * @param sizeBytes taille réellement écrite
 * @param sha256 empreinte en minuscules, calculée pendant l'écriture
 */
record StoredFile(Path path, long sizeBytes, String sha256) {}
