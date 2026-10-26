package ci.cnam.dqanalyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** Les traitements longs (import, analyse) tournent en @Async : le contrôleur rend la main aussitôt. */
@SpringBootApplication
@EnableAsync
public class DqAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DqAnalyzerApplication.class, args);
    }
}
