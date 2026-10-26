package ci.cnam.dqanalyzer.security.infra;

import ci.cnam.dqanalyzer.security.CurrentUserProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Lit l'utilisateur connecté dans le contexte de sécurité, puis son identifiant en base. */
@Component
class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    private final AppUserRepository users;

    SecurityContextCurrentUserProvider(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public long currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Aucun utilisateur authentifié dans le contexte de sécurité");
        }
        String login = authentication.getName();
        return users.findByLoginIgnoreCase(login)
                .orElseThrow(() -> new IllegalStateException("Compte connecté introuvable en base"))
                .getId();
    }
}
