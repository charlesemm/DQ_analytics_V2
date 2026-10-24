package ci.cnam.dqanalyzer;

import org.springframework.boot.SpringApplication;

public class TestDqAnalyzerApplication {

    public static void main(String[] args) {
        SpringApplication.from(DqAnalyzerApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
