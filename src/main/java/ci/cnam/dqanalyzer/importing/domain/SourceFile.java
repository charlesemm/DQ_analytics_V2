package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import ci.cnam.dqanalyzer.importing.ImportStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Fichier importé et conservé : nom d'origine, taille, empreinte SHA-256 (IMP-06), plus l'avancement
 * de son chargement vers DuckDB.
 *
 * <p>Le fichier lui-même n'est pas en base : {@code storagePath} pointe sa copie sur le disque, sous
 * un nom tiré au hasard — le nom donné par l'utilisateur n'est jamais utilisé comme nom de fichier.
 */
@Entity
@Table(name = "source_file")
public class SourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_name", nullable = false, length = 500)
    private String originalName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportFormat format;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "import_settings", nullable = false)
    private String importSettings;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "column_count")
    private Integer columnCount;

    @Column(name = "storage_path", length = 1000)
    private String storagePath;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt = Instant.now();

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_status", nullable = false, length = 10)
    private ImportStatus importStatus = ImportStatus.PENDING;

    @Column(name = "import_progress", nullable = false)
    private int importProgress;

    @Column(name = "import_error", length = 1000)
    private String importError;

    @Column(name = "ragged_row_count")
    private Long raggedRowCount;

    @Column(name = "duckdb_path", length = 1000)
    private String duckdbPath;

    @Column(name = "table_name", length = 63)
    private String tableName;

    protected SourceFile() {
        // requis par JPA
    }

    public SourceFile(
            String originalName,
            long sizeBytes,
            String sha256,
            ImportFormat format,
            String importSettings,
            String storagePath,
            long uploadedBy) {
        this.originalName = originalName;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.format = format;
        this.importSettings = importSettings;
        this.storagePath = storagePath;
        this.uploadedBy = uploadedBy;
    }

    /** Le chargement démarre : les paramètres retenus peuvent différer de ceux détectés (IMP-03). */
    public void startLoading(String retainedSettings, String duckdbPath, String tableName) {
        this.importSettings = retainedSettings;
        this.duckdbPath = duckdbPath;
        this.tableName = tableName;
        this.importStatus = ImportStatus.RUNNING;
        this.importProgress = 0;
        this.importError = null;
    }

    public void reportProgress(int percent) {
        this.importProgress = Math.clamp(percent, 0, 100);
    }

    public void finishLoading(long rowCount, int columnCount, long raggedRowCount) {
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.raggedRowCount = raggedRowCount;
        this.importStatus = ImportStatus.DONE;
        this.importProgress = 100;
        this.importError = null;
    }

    /** @param reason message déjà rédigé pour l'analyste, sans aucune donnée personnelle */
    public void failLoading(String reason) {
        this.importStatus = ImportStatus.FAILED;
        this.importError = reason;
    }

    public Long getId() {
        return id;
    }

    public String getOriginalName() {
        return originalName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public ImportFormat getFormat() {
        return format;
    }

    public String getImportSettings() {
        return importSettings;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public Integer getColumnCount() {
        return columnCount;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public ImportStatus getImportStatus() {
        return importStatus;
    }

    public int getImportProgress() {
        return importProgress;
    }

    public String getImportError() {
        return importError;
    }

    public Long getRaggedRowCount() {
        return raggedRowCount;
    }

    public String getDuckdbPath() {
        return duckdbPath;
    }

    public String getTableName() {
        return tableName;
    }
}
