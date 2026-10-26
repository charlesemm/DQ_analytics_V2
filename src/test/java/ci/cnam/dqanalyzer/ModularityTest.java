package ci.cnam.dqanalyzer;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Vérifie qu'aucun module n'utilise les classes internes (api/, domain/, infra/) d'un autre module. */
class ModularityTest {

    @Test
    void les_modules_respectent_leurs_frontieres() {
        ApplicationModules.of(DqAnalyzerApplication.class).verify();
    }
}
