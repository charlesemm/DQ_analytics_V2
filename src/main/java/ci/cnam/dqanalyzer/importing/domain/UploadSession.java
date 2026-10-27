package ci.cnam.dqanalyzer.importing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Envoi d'un fichier par morceaux, en cours ou terminé (IMP-01). Les morceaux ne sont pas en base :
 * ce sont des fichiers sur le disque, et c'est leur présence qui dit ce qui est déjà arrivé. Cette
 * entité ne garde que ce qu'il faut pour reprendre l'envoi et pour savoir à qui il appartient.
 *
 * <p>Un envoi abandonné laisse ses morceaux sur le disque : la purge des fichiers de travail les
 * effacera avec le reste (SEC-04).
 */
@Entity
@Table(name = "upload_session")
public class UploadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** Taille annoncée par le navigateur, indicative : seule la taille reçue est contrôlée. */
    @Column(name = "total_size")
    private Long totalSize;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected UploadSession() {
        // requis par JPA
    }

    public UploadSession(String fileName, Long totalSize, int chunkSize, long createdBy) {
        this.fileName = fileName;
        this.totalSize = totalSize;
        this.chunkSize = chunkSize;
        this.createdBy = createdBy;
    }

    /** Les morceaux ont été assemblés : l'envoi est terminé et ses morceaux peuvent disparaître. */
    public void completed(Instant when) {
        this.completedAt = when;
    }

    /** Vrai si cet envoi appartient à cet utilisateur : personne d'autre n'y touche. */
    public boolean belongsTo(long userId) {
        return createdBy != null && createdBy == userId;
    }

    public Long getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public Long getTotalSize() {
        return totalSize;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
