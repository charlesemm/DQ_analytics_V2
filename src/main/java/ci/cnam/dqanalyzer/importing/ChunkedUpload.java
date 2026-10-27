package ci.cnam.dqanalyzer.importing;

import java.util.List;

/**
 * Où en est l'envoi d'un fichier par morceaux (IMP-01, PERF-02). C'est ce que le navigateur
 * interroge après une coupure réseau pour savoir quels morceaux il doit renvoyer : les morceaux déjà
 * reçus sont sur le disque du serveur et survivent même à son redémarrage.
 *
 * @param uploadId identifiant de l'envoi
 * @param fileName nom du fichier annoncé par l'analyste
 * @param chunkSize taille d'un morceau, en octets ; le dernier est plus petit
 * @param receivedChunks numéros des morceaux déjà reçus, dans l'ordre, à partir de 0
 * @param receivedBytes total déjà reçu, en octets
 */
public record ChunkedUpload(
        long uploadId, String fileName, int chunkSize, List<Integer> receivedChunks, long receivedBytes) {

    public ChunkedUpload {
        receivedChunks = List.copyOf(receivedChunks);
    }
}
