package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ImportStatus;
import ci.cnam.dqanalyzer.importing.domain.SourceFile;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceFileRepository extends JpaRepository<SourceFile, Long> {

    /** Fichiers chargés, du plus récent au plus ancien, pour les écrans qui en font choisir un. */
    List<SourceFile> findByImportStatusOrderByUploadedAtDesc(ImportStatus status, Limit limit);
}
