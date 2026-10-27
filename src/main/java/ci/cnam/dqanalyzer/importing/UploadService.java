package ci.cnam.dqanalyzer.importing;

import java.io.InputStream;

/**
 * Envoi d'un fichier par morceaux de {@code dq.upload.chunk-size}, avec reprise après une coupure
 * réseau (IMP-01, PERF-02). Un fichier de 2 Go ne peut pas traverser un réseau d'entreprise en une
 * seule requête : il arrive par tranches, et une tranche perdue se renvoie seule.
 *
 * <p>Un envoi appartient à l'analyste qui l'a ouvert : personne d'autre ne peut y ajouter un morceau
 * ni l'assembler.
 */
public interface UploadService {

    /**
     * Ouvre un envoi. Le nom est contrôlé tout de suite (IMP-05) : inutile de transférer 2 Go pour
     * refuser un format à la fin.
     *
     * @param totalSize taille annoncée par le navigateur, indicative ; 0 si elle est inconnue
     * @throws ImportRejectedException si le format du fichier n'est pas accepté
     */
    ChunkedUpload start(String fileName, long totalSize);

    /**
     * Écrit un morceau sur le disque. Un morceau déjà reçu est simplement réécrit : renvoyer deux
     * fois le même morceau après une coupure est sans conséquence.
     *
     * @param chunkNumber numéro du morceau, à partir de 0
     * @param content flux du morceau ; il est lu une seule fois et n'est pas fermé ici
     * @throws ImportRejectedException si le morceau est trop grand ou hors des limites du fichier
     * @throws java.util.NoSuchElementException si l'envoi n'existe pas, ou n'est pas le sien
     */
    ChunkedUpload receiveChunk(long uploadId, int chunkNumber, InputStream content);

    /**
     * @throws java.util.NoSuchElementException si l'envoi n'existe pas, ou n'est pas le sien
     */
    ChunkedUpload progressOf(long uploadId);

    /**
     * Assemble les morceaux dans l'ordre et remet le fichier à l'import, qui le contrôle et propose
     * ses paramètres de lecture comme pour un envoi direct.
     *
     * @throws ImportRejectedException s'il manque un morceau ou si le fichier assemblé est refusé
     * @throws java.util.NoSuchElementException si l'envoi n'existe pas, ou n'est pas le sien
     */
    ReceivedFile complete(long uploadId);
}
