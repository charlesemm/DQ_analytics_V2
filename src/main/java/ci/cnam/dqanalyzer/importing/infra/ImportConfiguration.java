package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.domain.ImportValidation;
import ci.cnam.dqanalyzer.importing.domain.TextFormatDetector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Branche les classes du domaine, qui restent volontairement sans annotation Spring : elles se
 * testent avec un simple {@code new}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImportProperties.class)
class ImportConfiguration {

    @Bean
    ImportValidation importValidation(ImportProperties properties) {
        return new ImportValidation(properties.upload().maxFileSize().toBytes());
    }

    @Bean
    TextFormatDetector textFormatDetector() {
        return new TextFormatDetector();
    }
}
