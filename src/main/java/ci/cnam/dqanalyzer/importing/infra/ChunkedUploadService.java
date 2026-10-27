package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ChunkedUpload;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ReceivedFile;
import ci.cnam.dqanalyzer.importing.UploadService;
import ci.cnam.dqanalyzer.importing.domain.ImportValidation;
import ci.cnam.dqanalyzer.importing.domain.UploadSession;
import ci.cnam.dqanalyzer.security.CurrentUserProvider;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Envoi par morceaux avec reprise (IMP-01, PERF-02). Chaque morceau est écrit sur le disque dès son
 * arrivée ; l'assemblage ne fait que les relire bout à bout et remettre le résultat à l'import, qui
 * le contrôle comme n'importe quel fichier reçu.
 *
 * <p>Rien n'est gardé en mémoire d'un morceau à l'autre : une coupure réseau, et même un
 * redémarrage du serveur, laissent l'envoi reprenable là où il s'était arrêté.
 */
@Service
class ChunkedUploadService implements UploadService {

    private static final Logger log = LoggerFactory.getLogger(ChunkedUploadService.class);

    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;

    private final UploadSessionRepository sessions;
    private final FileStorage storage;
    private final ImportValidation validation;
    private final ImportService imports;
    private final CurrentUserProvider currentUser;
    private final Clock clock;
    private final int chunkSize;
    private final long maxSizeBytes;

    ChunkedUploadService(
            UploadSessionRepository sessions,
            FileStorage storage,
            ImportValidation validation,
            ImportService imports,
            CurrentUserProvider currentUser,
            Clock clock,
            ImportProperties properties) {
        this.sessions = sessions;
        this.storage = storage;
        this.validation = validation;
        this.imports = imports;
        this.currentUser = currentUser;
        this.clock = clock;
        this.chunkSize = (int) properties.upload().chunkSize().toBytes();
        this.maxSizeBytes = properties.upload().maxFileSize().toBytes();
    }

    @Override
    public ChunkedUpload start(String fileName, long totalSize) {
        validation.checkName(fileName); // le format est refusé avant le transfert, pas après
        if (totalSize > maxSizeBytes) {
            throw new ImportRejectedException("import.error.fichier-trop-grand", maxSizeBytes / BYTES_PER_MEGABYTE);
        }
        var session = sessions.save(
                new UploadSession(fileName, totalSize <= 0 ? null : totalSize, chunkSize, currentUser.currentUserId()));
        log.info("Envoi {} ouvert, morceaux de {} octets", session.getId(), chunkSize);
        return progress(session);
    }

    @Override
    public ChunkedUpload receiveChunk(long uploadId, int chunkNumber, InputStream content) {
        var session = own(uploadId);
        if (chunkNumber < 0 || (long) chunkNumber * chunkSize >= maxSizeBytes) {
            throw new ImportRejectedException("import.error.morceau-hors-limites", chunkNumber);
        }
        // La limite porte sur ce qui est déjà arrivé : chaque morceau est de toute façon borné à
        // chunkSize par l'écriture, un fichier juste à la limite passe donc entier.
        if (storage.receivedBytes(uploadId) >= maxSizeBytes) {
            throw new ImportRejectedException("import.error.fichier-trop-grand", maxSizeBytes / BYTES_PER_MEGABYTE);
        }
        storage.writeChunk(uploadId, chunkNumber, content, chunkSize);
        return progress(session);
    }

    @Override
    public ChunkedUpload progressOf(long uploadId) {
        return progress(own(uploadId));
    }

    @Override
    public ReceivedFile complete(long uploadId) {
        var session = own(uploadId);
        var numbers = storage.chunkNumbers(uploadId);
        checkComplete(numbers);
        try (var assembled = storage.openAssembled(uploadId, numbers)) {
            var received = imports.receive(assembled, session.getFileName());
            session.completed(clock.instant());
            sessions.save(session);
            storage.deleteChunks(uploadId);
            log.info(
                    "Envoi {} assemblé en {} morceau(x) vers le fichier {}",
                    uploadId,
                    numbers.size(),
                    received.sourceFileId());
            return received;
        } catch (IOException e) {
            throw new ImportRejectedException("import.error.assemblage-impossible");
        }
    }

    /** Les morceaux doivent se suivre sans trou : il manquerait un bout du fichier au milieu. */
    private static void checkComplete(List<Integer> numbers) {
        if (numbers.isEmpty()) {
            throw new ImportRejectedException("import.error.envoi-vide");
        }
        for (int position = 0; position < numbers.size(); position++) {
            if (numbers.get(position) != position) {
                throw new ImportRejectedException("import.error.morceau-manquant", position);
            }
        }
    }

    /**
     * Envoi de l'utilisateur courant. Un envoi qui n'est pas le sien est traité comme inexistant :
     * la réponse ne révèle pas qu'un autre analyste est en train d'envoyer un fichier (SEC-02).
     */
    private UploadSession own(long uploadId) {
        return sessions.findById(uploadId)
                .filter(session -> session.belongsTo(currentUser.currentUserId()))
                .orElseThrow(() -> new NoSuchElementException("Envoi inconnu : " + uploadId));
    }

    private ChunkedUpload progress(UploadSession session) {
        long uploadId = session.getId();
        return new ChunkedUpload(
                uploadId,
                session.getFileName(),
                session.getChunkSize(),
                storage.chunkNumbers(uploadId),
                storage.receivedBytes(uploadId));
    }
}
