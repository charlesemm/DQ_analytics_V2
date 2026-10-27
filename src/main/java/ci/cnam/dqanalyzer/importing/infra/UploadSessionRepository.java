package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.domain.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;

interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {}
