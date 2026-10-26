package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.domain.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;

interface SourceFileRepository extends JpaRepository<SourceFile, Long> {}
