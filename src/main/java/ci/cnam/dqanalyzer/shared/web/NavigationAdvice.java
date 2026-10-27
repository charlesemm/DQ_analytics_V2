package ci.cnam.dqanalyzer.shared.web;

import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Ce que les menus ont besoin de savoir des droits de l'utilisateur : une entrée de menu qui mène à
 * un refus n'a rien à faire là (ERG-01). Les droits restent vérifiés sur chaque contrôleur par
 * {@code @PreAuthorize} (SEC-02) : ceci ne sert qu'à l'affichage.
 */
@ControllerAdvice
class NavigationAdvice {

    private static final Set<String> IMPORT_ROLES = Set.of("ROLE_ADMIN", "ROLE_ANALYST");

    @ModelAttribute
    void navigation(Model model) {
        model.addAttribute("canImport", hasAnyRole(IMPORT_ROLES));
    }

    private static boolean hasAnyRole(Set<String> roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(roles::contains);
    }
}
