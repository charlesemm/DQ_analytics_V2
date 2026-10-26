package ci.cnam.dqanalyzer.importing.infra;

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

    /** Ouvre la base, en la créant si le fichier n'existe pas encore. */
    public Connection open(Path database) throws SQLException {
        return DriverManager.getConnection(JDBC_PREFIX + database.toAbsolutePath());
    }
}
