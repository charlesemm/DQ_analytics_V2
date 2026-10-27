package ci.cnam.dqanalyzer.importing.infra;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Réglages de l'import, pris dans {@code application.yml} sous {@code dq} : rien n'est codé en dur.
 *
 * @param storage répertoire de travail ; prévoir 3 × la taille max par analyse simultanée (PERF-03)
 * @param upload taille maximale d'un fichier (PERF-02, IMP-05)
 */
@ConfigurationProperties("dq")
public record ImportProperties(Storage storage, Upload upload) {

    public record Storage(Path path) {}

    public record Upload(DataSize maxFileSize, DataSize chunkSize) {}
}
