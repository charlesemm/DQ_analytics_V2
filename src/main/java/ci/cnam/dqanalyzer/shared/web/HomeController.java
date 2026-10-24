package ci.cnam.dqanalyzer.shared.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Page d'accueil : point de départ des parcours (ERG-01 : toute action courante à 3 clics au plus). */
@Controller
class HomeController {

    @GetMapping("/")
    String home(Principal principal, Model model) {
        model.addAttribute("username", principal.getName());
        return "home";
    }
}
