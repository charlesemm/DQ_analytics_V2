package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportProgress;
import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.ImportService;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.ReceivedFile;
import ci.cnam.dqanalyzer.importing.domain.CsvFormatDetector;
import ci.cnam.dqanalyzer.importing.domain.ImportValidation;
import ci.cnam.dqanalyzer.importing.domain.SourceFile;
import ci.cnam.dqanalyzer.security.CurrentUserProvider;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Import d'un fichier texte délimité : réception (IMP-05, IMP-06, IMP-07) puis chargement en tâche de
 * fond vers DuckDB (PERF-03).
 *
 * <p>Aucune valeur de cellule n'est journalisée, jamais : les fichiers contiennent des données
 * personnelles d'assurés. Les traces se limitent à des identifiants techniques et des compteurs.
 */
@Service
class CsvImportService implements ImportService {

    private static final Logger log = LoggerFactory.getLogger(CsvImportService.class);

    /** Les noms de table sont fabriqués ici, jamais déduits du nom du fichier. */
    private static final String TABLE_PREFIX = "ds_";

    private final SourceFileRepository sourceFiles;
    private final FileStorage storage;
    private final ImportValidation validation;
    private final CsvFormatDetector detector;
    private final DuckDbCsvLoader loader;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper json;
    private final MessageSource messages;
    private final long maxSizeBytes;

    CsvImportService(
            SourceFileRepository sourceFiles,
            FileStorage storage,
            ImportValidation validation,
            CsvFormatDetector detector,
            DuckDbCsvLoader loader,
            CurrentUserProvider currentUser,
            ObjectMapper json,
            MessageSource messages,
            ImportProperties properties) {
        this.sourceFiles = sourceFiles;
        this.storage = storage;
        this.validation = validation;
        this.detector = detector;
        this.loader = loader;
        this.currentUser = currentUser;
        this.json = json;
        this.messages = messages;
        this.maxSizeBytes = properties.upload().maxFileSize().toBytes();
    }

    @Override
    public ReceivedFile receive(InputStream content, String originalName) {
        validation.checkName(originalName); // avant d'écrire : inutile de recopier un fichier refusé
        var stored = storage.store(content, maxSizeBytes);
        try {
            validation.checkAcceptable(stored.path(), originalName);
            var settings = detector.detect(stored.path());
            var sourceFile = sourceFiles.save(new SourceFile(
                    originalName,
                    stored.sizeBytes(),
                    stored.sha256(),
                    ImportFormat.DELIMITED,
                    toJson(settings),
                    stored.path().toString(),
                    currentUser.currentUserId()));
            log.info(
                    "Fichier {} reçu ({} octets), détection : encodage {}, {}",
                    sourceFile.getId(),
                    stored.sizeBytes(),
                    settings.charsetName(),
                    settings.hasHeader() ? "en-tête présent" : "sans en-tête");
            return new ReceivedFile(sourceFile.getId(), originalName, stored.sizeBytes(), stored.sha256(), settings);
        } catch (RuntimeException e) {
            storage.delete(stored.path()); // un fichier refusé ne reste pas sur le disque
            throw e;
        }
    }

    @Async
    @Override
    public void startLoad(long sourceFileId, ImportSettings settings) {
        var sourceFile = sourceFiles.findById(sourceFileId).orElseThrow(() -> unknown(sourceFileId));
        Path database = storage.databaseFor(sourceFileId);
        String tableName = TABLE_PREFIX + sourceFileId;
        sourceFile.startLoading(toJson(settings), database.toString(), tableName);
        sourceFiles.save(sourceFile);
        try {
            var imported = loader.load(
                    Path.of(sourceFile.getStoragePath()),
                    settings,
                    database,
                    tableName,
                    (percent, rowsLoaded) -> reportProgress(sourceFile, percent));
            sourceFile.finishLoading(imported.rowCount(), imported.columns().size(), imported.raggedRowCount());
            log.info(
                    "Fichier {} chargé dans {} : {} lignes, {} colonnes, {} ligne(s) incomplète(s)",
                    sourceFileId,
                    tableName,
                    imported.rowCount(),
                    imported.columns().size(),
                    imported.raggedRowCount());
        } catch (ImportRejectedException e) {
            sourceFile.failLoading(resolve(e));
            log.warn("Fichier {} refusé au chargement : {}", sourceFileId, e.messageKey());
        } catch (RuntimeException e) {
            sourceFile.failLoading(message("import.error.chargement-impossible"));
            log.error("Chargement du fichier {} impossible", sourceFileId, e);
        }
        sourceFiles.save(sourceFile);
    }

    @Override
    public ImportProgress progressOf(long sourceFileId) {
        var sourceFile = sourceFiles.findById(sourceFileId).orElseThrow(() -> unknown(sourceFileId));
        return new ImportProgress(
                sourceFileId,
                sourceFile.getImportStatus(),
                sourceFile.getImportProgress(),
                sourceFile.getRowCount() == null ? 0 : sourceFile.getRowCount(),
                sourceFile.getRaggedRowCount() == null ? 0 : sourceFile.getRaggedRowCount(),
                sourceFile.getImportError() == null ? "" : sourceFile.getImportError());
    }

    private void reportProgress(SourceFile sourceFile, int percent) {
        sourceFile.reportProgress(percent);
        sourceFiles.save(sourceFile);
    }

    private String toJson(ImportSettings settings) {
        return json.writeValueAsString(settings);
    }

    /**
     * Le message est enregistré rédigé, en français : l'outil n'a qu'une langue (CDC), et le
     * chargement se déroule hors de toute requête, donc hors de la langue d'un utilisateur.
     */
    private String resolve(ImportRejectedException e) {
        return messages.getMessage(e.messageKey(), e.arguments().toArray(), Locale.FRENCH);
    }

    private String message(String key) {
        return messages.getMessage(key, null, Locale.FRENCH);
    }

    private static NoSuchElementException unknown(long sourceFileId) {
        return new NoSuchElementException("Import inconnu : " + sourceFileId);
    }
}
