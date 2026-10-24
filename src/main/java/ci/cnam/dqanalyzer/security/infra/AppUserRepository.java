package ci.cnam.dqanalyzer.security.infra;

import ci.cnam.dqanalyzer.security.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByLoginIgnoreCase(String login);
}
