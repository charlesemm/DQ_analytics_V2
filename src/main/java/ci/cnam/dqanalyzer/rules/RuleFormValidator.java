package ci.cnam.dqanalyzer.rules;

import ci.cnam.dqanalyzer.rules.RuleCatalogService.RuleForm;
import ci.cnam.dqanalyzer.rules.expression.Condition;
import ci.cnam.dqanalyzer.rules.expression.ConditionParser;
import ci.cnam.dqanalyzer.rules.expression.NumericExpression;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbDatabases;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Vérifie les paramètres d'une règle avant de l'enregistrer (REG-02) : l'évaluateur est exécuté sur
 * une table vide qui porte les colonnes de la règle. Un paramètre manquant, un opérateur inconnu ou
 * une condition mal formée remontent alors en {@link RuleEvaluationException}, avec un message
 * destiné à l'analyste, plutôt qu'au lancement de la première analyse.
 */
@Component
public class RuleFormValidator {

    private static final String TABLE = "regle_a_valider";
    private static final String REFERENCE_TABLE = "reference_a_valider";

    private final RuleEvaluatorRegistry registry;
    private final DuckDbDatabases databases;

    public RuleFormValidator(RuleEvaluatorRegistry registry, DuckDbDatabases databases) {
        this.registry = registry;
        this.databases = databases;
    }

    /** @throws RuleEvaluationException si la règle ne peut pas s'exécuter telle qu'elle est saisie */
    public void validate(RuleForm form) {
        var evaluator = registry.get(form.type());
        var columns = columnsOf(form);
        var rule = new RuleDefinition(
                null,
                "R-0000",
                0,
                form.name(),
                form.type(),
                evaluator.dimension(),
                form.targetColumns(),
                form.params(),
                form.normalization(),
                form.severity(),
                form.thresholdPercent(),
                form.message());
        try (Connection connection = databases.openInMemory()) {
            createEmptyTable(connection, TABLE, columns);
            createEmptyTable(connection, REFERENCE_TABLE, referenceColumnsOf(form));
            evaluator.evaluate(
                    new Dataset(connection, TABLE, List.copyOf(columns)),
                    rule,
                    referenceTableId -> REFERENCE_TABLE,
                    AnomalySink.DISCARD);
        } catch (SQLException e) {
            throw new RuleEvaluationException("La règle n'a pas pu être vérifiée : " + e.getMessage());
        }
    }

    /**
     * Colonnes que la table de vérification doit porter : celles que la règle cible, plus celles
     * citées par ses conditions et ses calculs — sinon {@link Dataset#quotedColumn} les refuserait.
     */
    private LinkedHashSet<String> columnsOf(RuleForm form) {
        var columns = new LinkedHashSet<>(form.targetColumns());
        var params = form.params() == null ? Map.<String, Object>of() : form.params();
        for (String name : List.of("when", "then")) {
            if (params.get(name) != null) {
                collect(ConditionParser.parse(params.get(name)), columns);
            }
        }
        if (params.get("expression") != null) {
            columns.addAll(NumericExpression.columns(params.get("expression")));
        }
        if (columns.isEmpty()) {
            throw new RuleEvaluationException("La règle doit cibler au moins une colonne");
        }
        return columns;
    }

    /** Colonnes attendues dans la table de référence par EXA-01 et INT-01. */
    private LinkedHashSet<String> referenceColumnsOf(RuleForm form) {
        var columns = new LinkedHashSet<String>();
        var params = form.params() == null ? Map.<String, Object>of() : form.params();
        for (String name : List.of("referenceKey", "referenceValue", "referenceColumn")) {
            Object value = params.get(name);
            if (value != null && !value.toString().isBlank()) {
                columns.add(value.toString());
            }
        }
        if (columns.isEmpty()) {
            columns.add("valeur");
        }
        return columns;
    }

    private static void collect(Condition condition, LinkedHashSet<String> columns) {
        switch (condition) {
            case Condition.All all -> all.conditions().forEach(c -> collect(c, columns));
            case Condition.Any any -> any.conditions().forEach(c -> collect(c, columns));
            case Condition.Not not -> collect(not.condition(), columns);
            case Condition.Comparison comparison -> columns.add(comparison.column());
        }
    }

    /**
     * Table sans aucune ligne : l'évaluateur produit alors 0 ligne contrôlée, mais tout son SQL est
     * bien construit et exécuté, ce qui suffit à valider les paramètres.
     */
    private static void createEmptyTable(Connection connection, String table, LinkedHashSet<String> columns)
            throws SQLException {
        var ddl = new StringBuilder("CREATE TABLE ")
                .append(DuckDbIdentifiers.quote(table))
                .append(" (")
                .append(DuckDbIdentifiers.quote(Dataset.ROW_NUM_COLUMN))
                .append(" BIGINT");
        for (String column : columns) {
            ddl.append(", ").append(DuckDbIdentifiers.quote(column)).append(" VARCHAR");
        }
        ddl.append(")");
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl.toString());
        }
    }
}
