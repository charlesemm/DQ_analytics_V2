package ci.cnam.dqanalyzer.importing.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.ImportedTable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lecture en flux d'un CSV vers une table DuckDB par l'appender (PERF-03). Le n° de ligne d'origine
 * est conservé dans {@code _row_num} : en-tête = ligne 1, première donnée = ligne 2, comme dans les
 * jeux de test des évaluateurs.
 */
class DuckDbCsvLoaderTest {

    private static final ImportSettings SEMICOLON_UTF8 = new ImportSettings("UTF-8", ';', '"', true);

    private final DuckDbDatabases databases = new DuckDbDatabases();
    private final DuckDbCsvLoader loader = new DuckDbCsvLoader(databases);

    @Test
    void charge_les_lignes_avec_leur_numero_d_origine(@TempDir Path directory) throws Exception {
        Path csv = csv(
                directory,
                """
                id;nom;ville
                1;Koné Aminata;Abidjan
                2;Yao Désiré;Bouaké
                3;Traoré Fanta;Korhogo
                """);

        var imported = loader.load(csv, SEMICOLON_UTF8, directory.resolve("analyse.duckdb"), "ds_1");

        assertThat(imported.columns()).containsExactly("id", "nom", "ville");
        assertThat(imported.rowCount()).isEqualTo(3);
        assertThat(imported.raggedRowCount()).isZero();
        assertThat(rows(imported, "_row_num, id, nom, ville"))
                .containsExactly(
                        List.of("2", "1", "Koné Aminata", "Abidjan"),
                        List.of("3", "2", "Yao Désiré", "Bouaké"),
                        List.of("4", "3", "Traoré Fanta", "Korhogo"));
    }

    @Test
    void la_table_reste_lisible_apres_fermeture_de_la_connexion(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("analyse.duckdb");
        var settings = new ImportSettings("UTF-8", ',', '"', true);

        var imported = loader.load(fixture("utf8_virgule.csv"), settings, database, "ds_2");

        assertThat(Files.exists(database)).isTrue();
        assertThat(rows(imported, "_row_num")).hasSize(3);
    }

    @Test
    void lit_un_fichier_windows_1252(@TempDir Path directory) throws Exception {
        var settings = new ImportSettings("windows-1252", ';', '"', true);

        var imported =
                loader.load(fixture("win1252_pointvirgule.csv"), settings, directory.resolve("w.duckdb"), "ds_3");

        assertThat(rows(imported, "nom")).contains(List.of("Yao Désiré"));
    }

    @Test
    void retire_la_marque_d_ordre_d_octets_du_premier_entete(@TempDir Path directory) throws Exception {
        var imported = loader.load(
                fixture("utf8_bom_pointvirgule.csv"), SEMICOLON_UTF8, directory.resolve("b.duckdb"), "ds_4");

        assertThat(imported.columns()).containsExactly("id", "nom", "ville");
    }

    @Test
    void transforme_une_cellule_vide_en_valeur_absente(@TempDir Path directory) throws Exception {
        Path csv =
                csv(directory, """
                id;nom
                1;
                2;Yao
                """);

        var imported = loader.load(csv, SEMICOLON_UTF8, directory.resolve("n.duckdb"), "ds_5");

        assertThat(count(imported, "nom IS NULL")).isEqualTo(1);
    }

    @Test
    void complete_les_lignes_trop_courtes_et_les_compte(@TempDir Path directory) throws Exception {
        Path csv = csv(
                directory,
                """
                id;nom;ville
                1;Koné;Abidjan
                2;Yao
                3;Traoré;Korhogo;en trop
                """);

        var imported = loader.load(csv, SEMICOLON_UTF8, directory.resolve("r.duckdb"), "ds_6");

        assertThat(imported.rowCount()).isEqualTo(3);
        assertThat(imported.raggedRowCount()).isEqualTo(2);
        assertThat(count(imported, "ville IS NULL")).isEqualTo(1);
        assertThat(rows(imported, "nom")).contains(List.of("Traoré"));
    }

    @Test
    void rend_les_entetes_en_doublon_utilisables(@TempDir Path directory) throws Exception {
        Path csv = csv(directory, """
                nom;nom;
                a;b;c
                """);

        var imported = loader.load(csv, SEMICOLON_UTF8, directory.resolve("d.duckdb"), "ds_7");

        assertThat(imported.columns()).containsExactly("nom", "nom_2", "colonne_3");
    }

    @Test
    void numerote_a_partir_de_un_quand_le_fichier_n_a_pas_d_entete(@TempDir Path directory) throws Exception {
        Path csv = csv(directory, """
                1;Koné;Abidjan
                2;Yao;Bouaké
                """);

        var imported = loader.load(csv, SEMICOLON_UTF8.withHasHeader(false), directory.resolve("s.duckdb"), "ds_8");

        assertThat(imported.columns()).containsExactly("colonne_1", "colonne_2", "colonne_3");
        assertThat(imported.rowCount()).isEqualTo(2);
        assertThat(rows(imported, "_row_num")).containsExactly(List.of("1"), List.of("2"));
    }

    @Test
    void remplace_la_base_laissee_par_un_chargement_precedent(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("analyse.duckdb");
        Path premier = csv(directory, """
                id;nom
                1;Koné
                """);
        Path second = csv(
                directory,
                """
                id;nom
                1;Koné
                2;Yao
                3;Traoré
                """);
        loader.load(premier, SEMICOLON_UTF8, database, "ds_1");

        var imported = loader.load(second, SEMICOLON_UTF8, database, "ds_1");

        assertThat(imported.rowCount()).isEqualTo(3);
        assertThat(rows(imported, "_row_num")).hasSize(3);
    }

    @Test
    void refuse_un_fichier_sans_aucune_ligne_de_donnee(@TempDir Path directory) {
        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() ->
                        loader.load(fixture("entete_seule.csv"), SEMICOLON_UTF8, directory.resolve("v.duckdb"), "ds_9"))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.aucune-donnee");
    }

    @Test
    void signale_la_ligne_ou_la_lecture_echoue_et_ne_laisse_pas_de_base_a_moitie_chargee(@TempDir Path directory)
            throws IOException {
        Path csv = csv(directory, """
                id;nom
                1;"abc"def"
                """);
        Path database = directory.resolve("k.duckdb");

        assertThatExceptionOfType(ImportRejectedException.class)
                .isThrownBy(() -> loader.load(csv, SEMICOLON_UTF8, database, "ds_10"))
                .extracting(ImportRejectedException::messageKey)
                .isEqualTo("import.error.ligne-illisible");
        assertThat(Files.exists(database)).isFalse();
    }

    @Test
    void charge_un_gros_fichier_en_flux_et_annonce_sa_progression(@TempDir Path directory) throws Exception {
        int expectedRows = 200_000;
        Path csv = directory.resolve("gros.csv");
        try (var writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("id;nom;ville\n");
            for (int i = 1; i <= expectedRows; i++) {
                writer.write(i + ";nom" + i + ";Abidjan\n");
            }
        }
        var reportedRows = new ArrayList<Long>();
        var reportedPercents = new ArrayList<Integer>();

        var imported = loader.load(csv, SEMICOLON_UTF8, directory.resolve("gros.duckdb"), "ds_11", (percent, rows) -> {
            reportedPercents.add(percent);
            reportedRows.add(rows);
        });

        assertThat(imported.rowCount()).isEqualTo(expectedRows);
        assertThat(reportedRows).isNotEmpty().isSorted().last().isEqualTo((long) expectedRows);
        assertThat(reportedPercents).isSorted().allSatisfy(percent -> assertThat(percent)
                .isBetween(0, 99));
        assertThat(count(imported, "_row_num = " + (expectedRows + 1))).isEqualTo(1);
    }

    private Path csv(Path directory, String content) throws IOException {
        Path file = directory.resolve("donnees" + Math.abs(content.hashCode()) + ".csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** Relit la table dans une connexion neuve : ce qui a été chargé est bien sur le disque. */
    private List<List<String>> rows(ImportedTable imported, String selection) throws SQLException {
        var result = new ArrayList<List<String>>();
        try (var connection = databases.open(imported.database());
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT " + selection + " FROM \"" + imported.tableName() + "\" ORDER BY " + "_row_num")) {
            while (rows.next()) {
                var row = new ArrayList<String>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    row.add(rows.getString(column));
                }
                result.add(row);
            }
        }
        return result;
    }

    private long count(ImportedTable imported, String condition) throws SQLException {
        try (var connection = databases.open(imported.database());
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT count(*) FROM \"" + imported.tableName() + "\" WHERE " + condition)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Path fixture(String name) {
        var url = Objects.requireNonNull(
                DuckDbCsvLoaderTest.class.getClassLoader().getResource("imports/" + name), "fixture " + name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
