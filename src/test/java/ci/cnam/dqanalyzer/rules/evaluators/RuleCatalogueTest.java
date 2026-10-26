package ci.cnam.dqanalyzer.rules.evaluators;

import static org.assertj.core.api.Assertions.assertThat;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluator;
import ci.cnam.dqanalyzer.rules.Severity;
import ci.cnam.dqanalyzer.testsupport.TestDatasets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Les 23 types de règles sur un même jeu de 5 lignes à anomalies connues (datasets/catalogue_regles.csv).
 * Pour chaque cas : le nombre de lignes contrôlées et les n° de lignes en anomalie attendus, vérifiés à la main.
 * C'est le premier critère de recette du CDC (§10).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleCatalogueTest {

    /** Date du jour figée pour les règles d'actualité : le test donne le même résultat demain. */
    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);

    private static final Normalization NONE = Normalization.NONE;
    private static final Normalization TRIM = new Normalization(true, false, false);
    private static final Normalization TRIM_UPPER = new Normalization(true, true, false);
    private static final Normalization ALL = new Normalization(true, true, true);

    private Connection connection;
    private Dataset dataset;
    private final ReferenceProvider refs = id -> "ref_communes";

    @BeforeAll
    void load() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        dataset = TestDatasets.loadCsv(connection, "datasets/catalogue_regles.csv");
        try (var st = connection.createStatement()) {
            st.execute("CREATE TABLE ref_communes (code VARCHAR, nom VARCHAR)");
            st.execute("INSERT INTO ref_communes VALUES ('001', 'PLATEAU'), ('002', 'COCODY'), ('003', 'YOPOUGON')");
        }
    }

    @AfterAll
    void close() throws Exception {
        connection.close();
    }

    Stream<Arguments> cases() {
        return Stream.of(
                // nom du cas, évaluateur, colonnes, paramètres, normalisation, lignes contrôlées, lignes en anomalie
                Arguments.of(
                        "CMP-01 vide",
                        new Cmp01RequiredValueEvaluator(),
                        List.of("num_secu"),
                        Map.of(),
                        NONE,
                        5,
                        List.of(4L)),
                Arguments.of(
                        "CMP-02 bouche-trou",
                        new Cmp02PlaceholderValueEvaluator(),
                        List.of("date_naiss"),
                        Map.of("values", List.of("01/01/1900", "N/A", "0")),
                        TRIM_UPPER,
                        5,
                        List.of(4L, 5L)),
                Arguments.of(
                        "CMP-03 si ayant droit",
                        new Cmp03ConditionalRequiredEvaluator(),
                        List.of("num_principal"),
                        Map.of("when", Map.of("column", "statut", "op", "eq", "value", "Ayant droit")),
                        NONE,
                        3,
                        List.of(3L)),
                Arguments.of(
                        "CMP-04 remplissage",
                        new Cmp04FillRateEvaluator(),
                        List.of("tel"),
                        Map.of(),
                        NONE,
                        5,
                        List.of(6L)),
                Arguments.of(
                        "VAL-01 entier",
                        new Val01DataTypeEvaluator(),
                        List.of("age"),
                        Map.of("dataType", "entier"),
                        NONE,
                        5,
                        List.of(3L)),
                Arguments.of(
                        "VAL-01 date",
                        new Val01DataTypeEvaluator(),
                        List.of("date_naiss"),
                        Map.of("dataType", "date"),
                        NONE,
                        5,
                        List.of(3L)),
                Arguments.of(
                        "VAL-02 téléphone CI",
                        new Val02PatternEvaluator(),
                        List.of("tel"),
                        Map.of("preset", "telephone_ci"),
                        NONE,
                        4,
                        List.of(3L)),
                Arguments.of(
                        "VAL-02 e-mail",
                        new Val02PatternEvaluator(),
                        List.of("email"),
                        Map.of("preset", "email"),
                        NONE,
                        3,
                        List.of(3L)),
                Arguments.of(
                        "VAL-03 longueur 13",
                        new Val03LengthEvaluator(),
                        List.of("num_secu"),
                        Map.of("min", 13, "max", 13),
                        NONE,
                        4,
                        List.of()),
                Arguments.of(
                        "VAL-04 âge 0-120",
                        new Val04RangeEvaluator(),
                        List.of("age"),
                        Map.of("min", 0, "max", 120),
                        NONE,
                        5,
                        List.of(3L, 4L)),
                Arguments.of(
                        "VAL-05 sexe",
                        new Val05AllowedValuesEvaluator(),
                        List.of("sexe"),
                        Map.of("values", "M; F"),
                        ALL,
                        5,
                        List.of(4L)),
                Arguments.of(
                        "VAL-06 jj/mm/aaaa",
                        new Val06DateFormatEvaluator(),
                        List.of("date_naiss"),
                        Map.of("formats", List.of("jj/mm/aaaa")),
                        NONE,
                        5,
                        List.of(3L)),
                Arguments.of(
                        "VAL-07 chiffres",
                        new Val07ForbiddenCharactersEvaluator(),
                        List.of("nom"),
                        Map.of("characters", "chiffres"),
                        NONE,
                        5,
                        List.of(6L)),
                Arguments.of(
                        "VAL-07 caractères spéciaux",
                        new Val07ForbiddenCharactersEvaluator(),
                        List.of("nom"),
                        Map.of("characters", "]-#"),
                        NONE,
                        5,
                        List.of()),
                Arguments.of(
                        "EXA-01 commune",
                        new Exa01ReferenceMatchEvaluator(),
                        List.of("code_commune", "commune"),
                        Map.of("referenceTableId", 1, "referenceKey", "code", "referenceValue", "nom"),
                        ALL,
                        5,
                        List.of(4L, 5L)),
                Arguments.of(
                        "EXA-02 Luhn",
                        new Exa02CheckDigitEvaluator(),
                        List.of("carte"),
                        Map.of("algorithm", "luhn"),
                        NONE,
                        3,
                        List.of(3L, 4L)),
                Arguments.of(
                        "EXA-02 modulo 97",
                        new Exa02CheckDigitEvaluator(),
                        List.of("nir"),
                        Map.of("algorithm", "mod97"),
                        NONE,
                        2,
                        List.of(3L)),
                Arguments.of(
                        "COH-01 début ≤ fin",
                        new Coh01ColumnComparisonEvaluator(),
                        List.of("date_debut", "date_fin"),
                        Map.of("operator", "lte", "valueType", "date"),
                        NONE,
                        4,
                        List.of(3L, 6L)),
                Arguments.of(
                        "COH-02 si M alors pas maternité",
                        new Coh02IfThenEvaluator(),
                        List.of("statut"),
                        Map.of(
                                "when",
                                Map.of("column", "sexe", "op", "eq", "value", "M"),
                                "then",
                                Map.of("column", "statut", "op", "ne", "value", "Maternité")),
                        NONE,
                        2,
                        List.of()),
                Arguments.of(
                        "COH-03 total",
                        new Coh03ComputedValueEvaluator(),
                        List.of("total"),
                        Map.of(
                                "expression",
                                Map.of(
                                        "op",
                                        "mul",
                                        "args",
                                        List.of(Map.of("column", "quantite"), Map.of("column", "prix")))),
                        NONE,
                        4,
                        List.of(3L)),
                Arguments.of(
                        "ACT-01 12 mois",
                        new Act01MaxAgeEvaluator(TODAY),
                        List.of("maj"),
                        Map.of("maxAge", 12, "unit", "mois"),
                        NONE,
                        4,
                        List.of(3L)),
                Arguments.of(
                        "ACT-02 non future",
                        new Act02NotInFutureEvaluator(TODAY),
                        List.of("date_naiss"),
                        Map.of(),
                        NONE,
                        4,
                        List.of(6L)),
                Arguments.of(
                        "INT-01 commune connue",
                        new Int01ReferenceExistsEvaluator(),
                        List.of("code_commune"),
                        Map.of("referenceTableId", 1, "referenceColumn", "code"),
                        TRIM,
                        5,
                        List.of(3L)),
                Arguments.of(
                        "UNI-01 n° unique",
                        new Uni01UniqueValueEvaluator(),
                        List.of("num_secu"),
                        Map.of(),
                        NONE,
                        4,
                        List.of(2L, 3L, 5L, 6L)),
                Arguments.of(
                        "UNI-02 nom + date",
                        new Uni02UniqueCompositeKeyEvaluator(),
                        List.of("nom", "date_naiss"),
                        Map.of(),
                        NONE,
                        5,
                        List.of()),
                Arguments.of(
                        "UNI-03 normalisé",
                        new Uni03NormalizedDuplicateEvaluator(),
                        List.of("nom"),
                        Map.of(),
                        ALL,
                        5,
                        List.of(2L, 3L)),
                Arguments.of(
                        "UNI-04 approché",
                        new Uni04ApproximateDuplicateEvaluator(),
                        List.of("nom"),
                        Map.of("algorithm", "jaro-winkler", "threshold", 0.9, "blockColumn", "date_naiss"),
                        ALL,
                        5,
                        List.of(4L, 5L)),
                Arguments.of(
                        "CMP-03 condition composée",
                        new Cmp03ConditionalRequiredEvaluator(),
                        List.of("num_principal"),
                        Map.of(
                                "when",
                                Map.of(
                                        "all",
                                        List.of(
                                                Map.of(
                                                        "column",
                                                        "statut",
                                                        "op",
                                                        "in",
                                                        "value",
                                                        List.of("Ayant droit", "Conjoint")),
                                                Map.of("column", "age", "op", "gte", "value", 18, "as", "number"),
                                                Map.of(
                                                        "not",
                                                        Map.of(
                                                                "column",
                                                                "date_naiss",
                                                                "op",
                                                                "lt",
                                                                "value",
                                                                "01/01/1950",
                                                                "as",
                                                                "date"))))),
                        NONE,
                        1,
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void regle_trouve_les_anomalies_attendues(
            String name,
            RuleEvaluator evaluator,
            List<String> columns,
            Map<String, Object> params,
            Normalization normalization,
            int expectedChecked,
            List<Long> expectedRows) {
        var rule = new RuleDefinition(
                1L,
                "R-" + evaluator.type(),
                1,
                name,
                evaluator.type(),
                evaluator.dimension(),
                columns,
                params,
                normalization,
                Severity.MAJOR,
                95,
                null);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, refs, anomalies::add);

        assertThat(result.checkedRows()).as("lignes contrôlées").isEqualTo(expectedChecked);
        assertThat(anomalies)
                .extracting(Anomaly::rowNum)
                .as("lignes en anomalie")
                .containsExactlyElementsOf(expectedRows);
        assertThat(result.anomalyRows()).isEqualTo(expectedRows.size());
    }
}
