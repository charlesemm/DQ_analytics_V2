package ci.cnam.dqanalyzer.importing.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/**
 * Ouverture des bases analytiques embarquées : un fichier {@code .duckdb} sur disque par analyse,
 * jamais en mémoire (PERF-03). Seul endroit du code qui connaît la forme de l'URL JDBC DuckDB.
 */
@Component
public class DuckDbDatabases {

    private static final String JDBC_PREFIX = "jdbc:duckdb:";

    /** Journal d'écriture que DuckDB pose à côté de la base. */
    private static final String WAL_SUFFIX = ".wal";

    /** Ouvre la base, en la créant si le fichier n'existe pas encore. */
    public Connection open(Path database) throws SQLException {
        return DriverManager.getConnection(JDBC_PREFIX + database.toAbsolutePath());
    }

    /** Base temporaire en mémoire : pour les lectures qui ne gardent rien, comme l'aperçu (IMP-04). */
    public Connection openInMemory() throws SQLException {
        return DriverManager.getConnection(JDBC_PREFIX);
    }

    /**
     * Efface la base et son journal. Un import écrit toujours une base neuve : un fichier qui
     * porterait déjà ce nom est le résidu d'un chargement abandonné, et sa table ferait échouer la
     * création de la nôtre.
     */
    public void delete(Path database) {
        try {
            Files.deleteIfExists(database);
            Files.deleteIfExists(Path.of(database + WAL_SUFFIX));
        } catch (IOException e) {
            // Rien à faire de plus : le fichier sera purgé avec le répertoire de travail (SEC-04).
        }
    }
}
