package ci.cnam.dqanalyzer.security.infra;

import java.time.Instant;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Charge un utilisateur depuis app_user pour Spring Security (USR-01, USR-02). */
@Service
class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) {
        var user = users.findByLoginIgnoreCase(login)
                .orElseThrow(() -> new UsernameNotFoundException("Identifiant ou mot de passe incorrect"));
        return User.withUsername(user.getLogin())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .disabled(!user.isActive())
                .accountLocked(user.isLockedAt(Instant.now()))
                .build();
    }
}
