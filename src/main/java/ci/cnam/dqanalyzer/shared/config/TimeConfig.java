package ci.cnam.dqanalyzer.shared.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Horloge de l'application. Les règles d'actualité la reçoivent en paramètre ; les tests passent une horloge fixe. */
@Configuration
class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
