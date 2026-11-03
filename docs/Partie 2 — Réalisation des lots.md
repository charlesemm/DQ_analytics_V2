# Partie 2 — Réalisation des lots

Cette partie construit, lot par lot, tout ce que le socle de la partie 1 ne contient pas encore, jusqu'à la recette. Pour chaque lot : le code à créer (complet quand il porte la logique métier), une explication, la demande à donner à Claude Code pour la partie écrans, les tests attendus et le critère de recette du CDC. Commence seulement quand `./mvnw verify` de la partie 1 passe au vert, et fais un lot par branche Git.

## Vue d'ensemble

Cette partie couvre tout ce qui reste après le socle : un lot par étape, chacun sur sa branche Git, fusionné seulement quand la CI (étape 15) est verte. Une flèche veut dire « doit être terminé avant ».

&#91;embedded content: ordre des lots 15 à 26 · une flèche = « doit être fini avant »\]

Le moteur de règles (16), l'import (17) et la double authentification (24) ne dépendent que du socle : tu peux les faire dans l'ordre que tu veux, ou demander à Claude Code d'avancer l'un pendant que tu relis l'autre. Tout converge vers l'exécution (21). La double authentification est placée à droite parce qu'elle ne bloque aucun écran, mais elle **doit être terminée avant que quiconque d'autre que toi utilise l'outil**, même en recette : les fichiers contiennent des données personnelles.

**Ce qui est déjà écrit et ce qui reste à générer.** Le code donné en entier ici (les 23 évaluateurs, le langage de conditions, l'import DuckDB, le profilage, l'exécution, le scoring, les migrations et les requêtes SQL) a été vérifié : les requêtes sur DuckDB et PostgreSQL, le Java compilé hors Maven. Les écrans, les exports, l'envoi par morceaux, le branchement de la double authentification et le LDAP sont décrits par des demandes à Claude Code, à relire. Dans les deux cas, la vérification qui compte est `./mvnw verify` sur ta machine, à chaque lot.

**Dans IntelliJ, pour chaque lot.** Le même cycle se répète de l'étape 15 à l'étape 26 :

1. **Créer la branche** : clic sur le nom de la branche en haut de la fenêtre → **New Branch**, avec le nom indiqué dans l'étape (`feat/rules`, `feat/mfa`…).
2. **Coller le code donné** dans les packages (copie, sélection du package dans l'onglet Project, **Ctrl+V** : voir étape 5) ; recharger Maven si le `pom.xml` a changé (**Ctrl+Maj+O**).
3. **Donner la demande à Claude Code** dans le terminal intégré (**Alt+F12**, puis `claude`), et relire ses modifications dans la vue de comparaison.
4. **Essayer** avec la configuration `DQ-Analyzer (dev)` (étape 2 bis.5) et les tests du lot (triangle vert).
5. **Vérifier** avec `./mvnw verify` (onglet Maven → Lifecycle → verify), puis **commit** (**Ctrl+K**) et **push** (**Ctrl+Maj+K**). La CI (étape 15) refait la vérification sur le serveur avant la fusion.

Les commandes du guide (`./mvnw`, `podman`, `duckdb`, `git`) se tapent toutes dans ce terminal intégré. Sous Windows, c'est PowerShell : remplace `./mvnw` par `.\mvnw.cmd`.

## Étape 15 — Intégration continue

À chaque envoi de code sur la forge, un serveur lance `./mvnw verify` (tests, format, couverture), puis construit l'image Podman de la branche principale. Une merge request dont les tests échouent ne peut pas être fusionnée. Le fichier ci-dessous est écrit pour **GitLab** (la forge la plus courante dans les DSI) ; la variante GitHub est donnée après. Fais ce lot en premier : chaque lot suivant sera alors vérifié automatiquement.

**15.1 — `.gitlab-ci.yml`** (à la racine du dépôt)

```yaml
# Pipeline DQ-Analyzer : vérification à chaque envoi, image Podman sur la branche principale.
stages:
  - verify
  - image

variables:
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"
  # Testcontainers parle au démon Docker du service « docker » ci-dessous
  DOCKER_HOST: "tcp://docker:2375"
  DOCKER_TLS_CERTDIR: ""
  TESTCONTAINERS_RYUK_DISABLED: "true"

cache:
  key: maven-$CI_COMMIT_REF_SLUG
  paths:
    - .m2/repository

verify:
  stage: verify
  image: docker.io/library/eclipse-temurin:21-jdk
  services:
    - name: docker.io/library/docker:27-dind
      alias: docker
  script:
    - chmod +x mvnw
    - ./mvnw -B verify
  artifacts:
    when: always
    reports:
      junit:
        - target/surefire-reports/TEST-*.xml
        - target/failsafe-reports/TEST-*.xml
    paths:
      - target/site/jacoco/
    expire_in: 14 days

image:
  stage: image
  image: quay.io/podman/stable:latest
  rules:
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
  script:
    - podman login -u "$CI_REGISTRY_USER" -p "$CI_REGISTRY_PASSWORD" "$CI_REGISTRY"
    - podman build -t "$CI_REGISTRY_IMAGE:$CI_COMMIT_SHORT_SHA" -t "$CI_REGISTRY_IMAGE:latest" .
    - podman push "$CI_REGISTRY_IMAGE:$CI_COMMIT_SHORT_SHA"
    - podman push "$CI_REGISTRY_IMAGE:latest"
```

**Explication bloc par bloc.**

- **`stages`** : les étapes, dans l'ordre. Si `verify` échoue, `image` ne se lance pas.
- **`MAVEN_OPTS` + `cache`** : les bibliothèques Maven sont rangées dans le dossier du projet et gardées d'un pipeline à l'autre ; le deuxième pipeline ne retélécharge rien et dure quelques minutes de moins.
- **`services: docker:27-dind`** (« Docker in Docker ») : les tests d'intégration démarrent un PostgreSQL avec Testcontainers ; ce service leur fournit un démon de conteneurs à l'intérieur du pipeline. `DOCKER_HOST` dit à Testcontainers où le trouver, `TESTCONTAINERS_RYUK_DISABLED` désactive son conteneur de nettoyage, inutile ici puisque tout le pipeline est détruit à la fin.
- **`./mvnw -B verify`** : exactement la commande que tu lances sur ton poste ; `-B` (batch) supprime les messages interactifs et les barres de progression dans les journaux.
- **`artifacts.reports.junit`** : GitLab lit les résultats des tests et les affiche dans la merge request (test par test, avec le message d'erreur). Le rapport de couverture JaCoCo est gardé 14 jours, téléchargeable depuis le pipeline.
- **`image`**, seulement sur la branche principale (`rules`) : construit l'image avec le même `Dockerfile` que ton poste et la pousse dans le registre d'images de GitLab, étiquetée par le numéro court du commit et par `latest`. Le serveur pourra ensuite récupérer une version précise (`podman pull`) au lieu de la reconstruire.
- **`$CI_REGISTRY_…`** : variables fournies automatiquement par GitLab ; aucun mot de passe n'est écrit dans le fichier.

**15.2 — Bloquer la fusion si le pipeline échoue.** Dans GitLab : *Settings → Merge requests → Merge checks*, coche « Pipelines must succeed ». Dans *Settings → Repository → Protected branches*, protège `main` : personne ne pousse directement dessus, tout passe par une merge request.

**Explication.** Sans ces deux réglages, le pipeline n'est qu'une indication : on peut fusionner un code qui casse les tests. Avec eux, `main` reste toujours déployable.

**À demander à l'administrateur GitLab de la DSI.** Le service « Docker in Docker » et la construction d'images demandent un runner GitLab configuré en mode `privileged`. Si la DSI le refuse, remplace l'étape `image` par une construction avec Kaniko ou Buildah sans privilège, et lance les tests d'intégration sur un runner dédié : décris la contrainte à Claude Code, il adaptera le fichier.

**15.3 — Variante GitHub : `.github/workflows/ci.yml`**

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest          # Docker est déjà installé : Testcontainers fonctionne tel quel
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
          cache: maven
      - run: ./mvnw -B verify
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: rapports
          path: |
            target/surefire-reports/
            target/failsafe-reports/
            target/site/jacoco/
```

Pour bloquer la fusion sur GitHub : *Settings → Branches → Add rule* sur `main`, « Require status checks to pass » avec le contrôle `verify`.

**Vérification de fin de lot.** Pousse une branche avec un test volontairement faux (`assertThat(1).isEqualTo(2)`) : le pipeline passe au rouge et la merge request refuse la fusion. Corrige, repousse : le pipeline passe au vert.

## Étape 16 — Les 22 règles restantes

Ce lot complète le catalogue du CDC §6 : 18 règles ligne à ligne s'appuient sur `RowPredicateEvaluator`, les 4 règles d'unicité ont leur propre classe de base. Tout le code est donné ; il a été compilé en Java 21, et le SQL de chaque règle a été exécuté sur DuckDB 2.0 avec le jeu de test de 16.9, qui trouve exactement les anomalies attendues. Branche Git conseillée : `feat/catalogue-regles`.

### 16.1 — Deux ajouts au code de la partie 1

1. Dans `rules/RuleDefinition.java`, ajoute cette méthode sous `firstColumn()` :

```java
    /** Deuxième colonne ciblée : règles qui comparent deux colonnes (COH-01) ou une clé et une valeur (EXA-01). */
    public String secondColumn() {
        if (targetColumns.size() < 2) {
            throw new RuleEvaluationException("La règle " + ruleCode + " doit cibler deux colonnes");
        }
        return targetColumns.get(1);
    }
```

2. Crée `shared/config/TimeConfig.java` :

```java
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
```

**Explication.** Les règles ACT-01 et ACT-02 comparent des dates à « aujourd'hui ». Si elles appelaient directement `LocalDate.now()`, un test écrit aujourd'hui échouerait dans un an. En recevant une `Clock`, elles utilisent l'heure réelle en production et une date figée dans les tests.

### 16.2 — Outils communs aux règles

`rules/RuleParams.java` : lit les paramètres JSON d'une règle avec des messages clairs.

```java
package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lecture typée des paramètres JSON d'une règle, avec des messages d'erreur lisibles par l'analyste.
 * Les valeurs viennent de Jackson : texte, nombre, booléen, liste ou objet.
 */
public final class RuleParams {

    private final String ruleCode;
    private final Map<String, Object> values;

    private RuleParams(String ruleCode, Map<String, Object> values) {
        this.ruleCode = ruleCode;
        this.values = values;
    }

    public static RuleParams of(RuleDefinition rule) {
        return new RuleParams(rule.ruleCode(), rule.params());
    }

    public String text(String name) {
        return optionalText(name).orElseThrow(() -> missing(name));
    }

    public Optional<String> optionalText(String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.toString());
    }

    public String textOrDefault(String name, String defaultValue) {
        return optionalText(name).orElse(defaultValue);
    }

    public double number(String name) {
        return optionalNumber(name).orElseThrow(() -> missing(name));
    }

    public Optional<Double> optionalNumber(String name) {
        Object value = values.get(name);
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        if (value instanceof Number n) {
            return Optional.of(n.doubleValue());
        }
        try {
            return Optional.of(Double.parseDouble(value.toString().replace(',', '.')));
        } catch (NumberFormatException e) {
            throw new RuleEvaluationException(
                    "Règle " + ruleCode + " : le paramètre « " + name + " » doit être un nombre (valeur : " + value + ")");
        }
    }

    public List<String> textList(String name) {
        Object value = values.get(name);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value != null && !value.toString().isBlank()) {
            // Liste saisie sous forme de texte : « M; F » ou « M, F »
            return List.of(value.toString().split("\\s*[;,]\\s*"));
        }
        throw missing(name);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> object(String name) {
        Object value = values.get(name);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw missing(name);
    }

    public long longValue(String name) {
        return (long) number(name);
    }

    private RuleEvaluationException missing(String name) {
        return new RuleEvaluationException("Règle " + ruleCode + " : le paramètre « " + name + " » est obligatoire");
    }
}
```

`rules/DateFormats.java` : traduit les formats de date du métier en formats DuckDB.

```java
package ci.cnam.dqanalyzer.rules;

/** Traduit les formats de date saisis par l'analyste (jj/mm/aaaa) en formats DuckDB (%d/%m/%Y). */
public final class DateFormats {

    public static final String DEFAULT = "jj/mm/aaaa";

    private DateFormats() {}

    /** « jj/mm/aaaa » → « %d/%m/%Y ». Un format déjà écrit avec des % est laissé tel quel. */
    public static String toDuckDb(String format) {
        if (format.contains("%")) {
            return format;
        }
        return format.toLowerCase()
                .replace("aaaa", "%Y")
                .replace("jj", "%d")
                .replace("mm", "%m")
                .replace("hh", "%H")
                .replace("mi", "%M")
                .replace("ss", "%S");
    }
}
```

`rules/evaluators/Sql.java` : morceaux de SQL réutilisés par toutes les règles.

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Collections;
import java.util.function.UnaryOperator;

/** Morceaux de SQL DuckDB réutilisés par les évaluateurs. Toujours appelés avec des identifiants déjà cités. */
final class Sql {

    private Sql() {}

    /** La cellule contient autre chose que des espaces. */
    static SqlFragment filled(String column) {
        return SqlFragment.of(column + " IS NOT NULL AND trim(CAST(" + column + " AS VARCHAR)) <> ''");
    }

    /** La cellule est vide, absente ou ne contient que des espaces. */
    static SqlFragment empty(String column) {
        return SqlFragment.of(column + " IS NULL OR trim(CAST(" + column + " AS VARCHAR)) = ''");
    }

    /** Valeur numérique de la cellule (virgule décimale acceptée) ou NULL si ce n'est pas un nombre. */
    static String number(String column) {
        return "TRY_CAST(replace(trim(CAST(" + column + " AS VARCHAR)), ',', '.') AS DOUBLE)";
    }

    /** Date de la cellule selon un format DuckDB (un « ? » à lier) ou NULL si elle ne respecte pas le format. */
    static String date(String column) {
        return "CAST(try_strptime(trim(CAST(" + column + " AS VARCHAR)), ?) AS DATE)";
    }

    /** Une liste de n « ? » séparés par des virgules, chacun passé par la même normalisation que la colonne. */
    static String placeholders(int count, UnaryOperator<String> wrap) {
        return String.join(", ", Collections.nCopies(count, wrap.apply("?")));
    }
}
```

`rules/evaluators/FilledValueEvaluator.java` : base des règles qui contrôlent le contenu d'une cellule.

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;

/**
 * Base des règles qui contrôlent le contenu d'une cellule (validité, exactitude, actualité…).
 * Elles ne contrôlent que les cellules renseignées : une cellule vide relève de CMP-01, pas d'elles.
 * Sans cela, une colonne à moitié vide compterait deux fois ses cellules vides en anomalie.
 */
abstract class FilledValueEvaluator extends RowPredicateEvaluator {

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return Sql.filled(data.quotedColumn(rule.firstColumn()));
    }

    /** Valeur de la colonne après la normalisation choisie dans la règle (espaces, majuscules, accents). */
    protected String normalizedValue(Dataset data, RuleDefinition rule) {
        return rule.normalization().apply(data.quotedColumn(rule.firstColumn()));
    }
}
```

**Explication.**

- **`RuleParams`** : les paramètres d'une règle arrivent en JSON (texte, nombre, liste…). Sans cette classe, chaque règle devrait vérifier elle-même qu'un paramètre existe et le convertir. Ici, un paramètre manquant ou mal saisi produit toujours un message du type « Règle R-0012 : le paramètre « min » doit être un nombre », compréhensible par l'analyste (ERG-02). `textList` accepte aussi une liste tapée à la main (« M; F »), ce qui simplifiera les formulaires.
- **`DateFormats`** : le métier écrit « jj/mm/aaaa », DuckDB attend « %d/%m/%Y ». La traduction est faite à un seul endroit.
- **`Sql`** : la même façon de dire « cellule vide », « nombre » ou « date » dans toutes les règles. `Sql.number` accepte la virgule décimale française (« 10,5 »). `Sql.date` utilise `try_strptime`, qui renvoie NULL pour une date mal écrite ou impossible (le 31/02) au lieu de faire échouer la requête.
- **`FilledValueEvaluator`** : une règle de validité ne s'occupe pas des cellules vides. Sans ce principe, une cellule vide serait à la fois en anomalie CMP-01 et VAL-04, et compterait deux fois dans le score. Les lignes contrôlées par une règle de validité sont donc les lignes renseignées, comme le prévoit le CDC §7 (« lignes concernées par la règle »).

### 16.3 — Le langage contrôlé des conditions et des calculs

CMP-03, COH-02 et COH-03 demandent à l'analyste d'écrire une condition (« si statut = Ayant droit ») ou un calcul (« quantité × prix unitaire »). Le CDC §9.3 interdit le SQL libre : la condition est donc un petit arbre JSON, que le formulaire construira avec des listes déroulantes (colonne, opérateur, valeur), et que le code traduit en SQL sûr.

`rules/expression/Condition.java`

```java
package ci.cnam.dqanalyzer.rules.expression;

import java.util.List;

/**
 * Condition saisie par l'analyste (CMP-03, COH-02), sous forme d'arbre. Pas de texte libre : on ne peut
 * exprimer que ce qui est prévu ici, ce qui exclut toute injection SQL.
 *
 * <p>Forme JSON, par exemple « statut = Ayant droit ET âge ≥ 18 » :
 * <pre>{"all": [ {"column": "statut", "op": "eq", "value": "Ayant droit"},
 *            {"column": "age", "op": "gte", "value": 18, "as": "number"} ]}</pre>
 */
public sealed interface Condition {

    /** Toutes les conditions doivent être vraies (ET). */
    record All(List<Condition> conditions) implements Condition {
        public All {
            conditions = List.copyOf(conditions);
        }
    }

    /** Au moins une condition doit être vraie (OU). */
    record Any(List<Condition> conditions) implements Condition {
        public Any {
            conditions = List.copyOf(conditions);
        }
    }

    /** Inverse une condition (NON). */
    record Not(Condition condition) implements Condition {}

    /**
     * Compare une colonne à une valeur, ou teste si elle est vide ou renseignée.
     *
     * @param values une valeur pour eq, ne, lt…, plusieurs pour in / not_in, aucune pour empty / filled
     * @param valueType TEXT compare le texte, NUMBER et DATE convertissent d'abord la cellule
     * @param dateFormat format des dates de la colonne (jj/mm/aaaa par défaut), pour valueType = DATE
     */
    record Comparison(String column, Operator op, List<String> values, ValueType valueType, String dateFormat)
            implements Condition {
        public Comparison {
            values = List.copyOf(values);
        }
    }

    enum Operator {
        EQ("="),
        NE("<>"),
        LT("<"),
        LTE("<="),
        GT(">"),
        GTE(">="),
        IN("IN"),
        NOT_IN("NOT IN"),
        EMPTY(""),
        FILLED("");

        private final String sql;

        Operator(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }

    enum ValueType {
        TEXT,
        NUMBER,
        DATE
    }
}
```

`rules/expression/ConditionParser.java` : JSON → arbre, avec contrôle de chaque élément.

```java
package ci.cnam.dqanalyzer.rules.expression;

import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.expression.Condition.Comparison;
import ci.cnam.dqanalyzer.rules.expression.Condition.Operator;
import ci.cnam.dqanalyzer.rules.expression.Condition.ValueType;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Transforme le JSON d'une condition (déjà lu par Jackson en Map) en arbre {@link Condition}. */
public final class ConditionParser {

    private static final int MAX_DEPTH = 5;

    private ConditionParser() {}

    public static Condition parse(Object json) {
        return parse(json, 0);
    }

    @SuppressWarnings("unchecked")
    private static Condition parse(Object json, int depth) {
        if (depth > MAX_DEPTH) {
            throw new RuleEvaluationException("Condition trop imbriquée (" + MAX_DEPTH + " niveaux au plus)");
        }
        if (!(json instanceof Map<?, ?> raw)) {
            throw new RuleEvaluationException("Condition invalide : un objet est attendu");
        }
        var map = (Map<String, Object>) raw;
        if (map.containsKey("all")) {
            return new Condition.All(children(map.get("all"), depth));
        }
        if (map.containsKey("any")) {
            return new Condition.Any(children(map.get("any"), depth));
        }
        if (map.containsKey("not")) {
            return new Condition.Not(parse(map.get("not"), depth + 1));
        }
        return comparison(map);
    }

    private static List<Condition> children(Object list, int depth) {
        if (!(list instanceof List<?> items) || items.isEmpty()) {
            throw new RuleEvaluationException("Condition invalide : « all » et « any » attendent une liste non vide");
        }
        return items.stream().map(item -> parse(item, depth + 1)).toList();
    }

    private static Comparison comparison(Map<String, Object> map) {
        Object column = map.get("column");
        if (column == null || column.toString().isBlank()) {
            throw new RuleEvaluationException("Condition invalide : la colonne est obligatoire");
        }
        Operator op = enumValue(Operator.class, map.getOrDefault("op", "eq"), "opérateur");
        ValueType type = enumValue(ValueType.class, map.getOrDefault("as", "text"), "type");

        List<String> values;
        Object value = map.get("value");
        if (op == Operator.EMPTY || op == Operator.FILLED) {
            values = List.of();
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            values = list.stream().map(String::valueOf).toList();
        } else if (value != null) {
            values = List.of(value.toString());
        } else {
            throw new RuleEvaluationException("Condition sur « " + column + " » : une valeur est attendue");
        }
        boolean multiValue = op == Operator.IN || op == Operator.NOT_IN;
        if (!multiValue && values.size() > 1) {
            throw new RuleEvaluationException("Condition sur « " + column + " » : une seule valeur est attendue");
        }
        String dateFormat = String.valueOf(map.getOrDefault("dateFormat", DateFormats.DEFAULT));
        return new Comparison(column.toString(), op, values, type, dateFormat);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object raw, String label) {
        try {
            return Enum.valueOf(type, raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RuleEvaluationException("Condition invalide : " + label + " inconnu « " + raw + " »");
        }
    }
}
```

`rules/expression/ConditionCompiler.java` : arbre → SQL DuckDB.

```java
package ci.cnam.dqanalyzer.rules.expression;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.expression.Condition.All;
import ci.cnam.dqanalyzer.rules.expression.Condition.Any;
import ci.cnam.dqanalyzer.rules.expression.Condition.Comparison;
import ci.cnam.dqanalyzer.rules.expression.Condition.Not;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Traduit une {@link Condition} en SQL DuckDB. Les colonnes passent par {@link Dataset#quotedColumn}
 * (existence vérifiée, nom cité) et les valeurs deviennent des paramètres « ? ».
 *
 * <p>Une condition qui ne peut pas être évaluée (cellule vide, texte au lieu d'un nombre) vaut FAUX,
 * jamais « inconnu » : {@code coalesce(…, false)}.
 */
public final class ConditionCompiler {

    private ConditionCompiler() {}

    public static SqlFragment compile(Condition condition, Dataset data) {
        return switch (condition) {
            case All all -> join(all.conditions(), " AND ", data);
            case Any any -> join(any.conditions(), " OR ", data);
            case Not not -> {
                var inner = compile(not.condition(), data);
                yield new SqlFragment("NOT (" + inner.sql() + ")", inner.params());
            }
            case Comparison c -> comparison(c, data);
        };
    }

    private static SqlFragment join(List<Condition> conditions, String operator, Dataset data) {
        var parts = new ArrayList<String>();
        var params = new ArrayList<Object>();
        for (Condition child : conditions) {
            var fragment = compile(child, data);
            parts.add("(" + fragment.sql() + ")");
            params.addAll(fragment.params());
        }
        return new SqlFragment(String.join(operator, parts), params);
    }

    private static SqlFragment comparison(Comparison c, Dataset data) {
        String column = data.quotedColumn(c.column());
        String text = "trim(CAST(" + column + " AS VARCHAR))";
        switch (c.op()) {
            case EMPTY -> {
                return SqlFragment.of("coalesce(" + text + ", '') = ''");
            }
            case FILLED -> {
                return SqlFragment.of("coalesce(" + text + ", '') <> ''");
            }
            default -> {
                // comparaison : traitée plus bas
            }
        }

        var params = new ArrayList<Object>();
        String left;
        String placeholder;
        switch (c.valueType()) {
            case NUMBER -> {
                left = "TRY_CAST(replace(" + text + ", ',', '.') AS DOUBLE)";
                placeholder = "CAST(? AS DOUBLE)";
                for (String value : c.values()) {
                    params.add(parseNumber(value, c.column()));
                }
            }
            case DATE -> {
                params.add(DateFormats.toDuckDb(c.dateFormat()));
                left = "CAST(try_strptime(" + text + ", ?) AS DATE)";
                placeholder = "CAST(? AS DATE)";
                for (String value : c.values()) {
                    params.add(isoDate(value, c.column()));
                }
            }
            default -> {
                left = text;
                placeholder = "?";
                params.addAll(c.values());
            }
        }

        String sql = switch (c.op()) {
            case IN, NOT_IN -> left + " " + c.op().sql() + " ("
                    + String.join(", ", Collections.nCopies(c.values().size(), placeholder)) + ")";
            default -> left + " " + c.op().sql() + " " + placeholder;
        };
        return new SqlFragment("coalesce(" + sql + ", false)", params);
    }

    private static Double parseNumber(String value, String column) {
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new RuleEvaluationException("Condition sur « " + column + " » : « " + value + " » n'est pas un nombre");
        }
    }

    /** La valeur d'une condition de date s'écrit jj/mm/aaaa ou aaaa-mm-jj ; elle est passée à DuckDB en ISO. */
    private static String isoDate(String value, String column) {
        String v = value.trim();
        if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return v;
        }
        if (v.matches("\\d{2}/\\d{2}/\\d{4}")) {
            return v.substring(6) + "-" + v.substring(3, 5) + "-" + v.substring(0, 2);
        }
        throw new RuleEvaluationException(
                "Condition sur « " + column + " » : la date « " + value + " » doit s'écrire jj/mm/aaaa");
    }
}
```

`rules/expression/NumericExpression.java` : les calculs de COH-03.

```java
package ci.cnam.dqanalyzer.rules.expression;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calcul saisi par l'analyste pour COH-03 (« total = quantité × prix unitaire »), sous forme d'arbre JSON :
 * <pre>{"op": "mul", "args": [ {"column": "quantite"}, {"column": "prix_unitaire"} ]}</pre>
 * Opérations : add, sub, mul, div ; feuilles : {"column": …} ou {"value": nombre}.
 */
public final class NumericExpression {

    private static final Map<String, String> OPERATORS = Map.of("add", "+", "sub", "-", "mul", "*", "div", "/");
    private static final int MAX_DEPTH = 5;

    private NumericExpression() {}

    /** SQL de l'expression ; NULL si une des cellules n'est pas un nombre ou en cas de division par zéro. */
    public static SqlFragment compile(Object json, Dataset data) {
        return compile(json, data, 0);
    }

    /** Colonnes utilisées par l'expression, pour restreindre les lignes contrôlées à celles qui sont remplies. */
    public static Set<String> columns(Object json) {
        var result = new java.util.LinkedHashSet<String>();
        collect(json, result, 0);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static SqlFragment compile(Object json, Dataset data, int depth) {
        if (depth > MAX_DEPTH) {
            throw new RuleEvaluationException("Calcul trop imbriqué (" + MAX_DEPTH + " niveaux au plus)");
        }
        if (!(json instanceof Map<?, ?> raw)) {
            throw new RuleEvaluationException("Calcul invalide : un objet est attendu");
        }
        var map = (Map<String, Object>) raw;
        if (map.containsKey("column")) {
            String column = data.quotedColumn(String.valueOf(map.get("column")));
            return SqlFragment.of("TRY_CAST(replace(trim(CAST(" + column + " AS VARCHAR)), ',', '.') AS DOUBLE)");
        }
        if (map.containsKey("value")) {
            try {
                return SqlFragment.of(
                        "CAST(? AS DOUBLE)", Double.parseDouble(String.valueOf(map.get("value")).replace(',', '.')));
            } catch (NumberFormatException e) {
                throw new RuleEvaluationException("Calcul invalide : « " + map.get("value") + " » n'est pas un nombre");
            }
        }
        String op = OPERATORS.get(String.valueOf(map.get("op")));
        if (op == null || !(map.get("args") instanceof List<?> args) || args.size() != 2) {
            throw new RuleEvaluationException("Calcul invalide : op doit valoir add, sub, mul ou div, avec 2 arguments");
        }
        var left = compile(args.get(0), data, depth + 1);
        var right = compile(args.get(1), data, depth + 1);
        var params = new ArrayList<>(left.params());
        params.addAll(right.params());
        String rightSql = op.equals("/") ? "nullif(" + right.sql() + ", 0)" : right.sql();
        return new SqlFragment("(" + left.sql() + " " + op + " " + rightSql + ")", params);
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object json, Set<String> result, int depth) {
        if (depth > MAX_DEPTH || !(json instanceof Map<?, ?> raw)) {
            return;
        }
        var map = (Map<String, Object>) raw;
        if (map.containsKey("column")) {
            result.add(String.valueOf(map.get("column")));
        } else if (map.get("args") instanceof List<?> args) {
            args.forEach(arg -> collect(arg, result, depth + 1));
        }
    }
}
```

**Explication.**

- **Pourquoi un arbre et pas une phrase** : analyser une phrase comme « statut = 'Ayant droit' ET age >= 18 » demanderait un vrai analyseur de langage, avec ses pièges. Un arbre JSON se construit directement depuis un formulaire (une ligne par condition, un menu ET/OU), se valide élément par élément, et ne peut contenir que les opérateurs prévus.
- **`sealed interface` + `record`** : Java sait qu'une `Condition` est exactement l'une des quatre formes (ET, OU, NON, comparaison). Le `switch` de `ConditionCompiler` n'a donc pas de cas « autre » : si on ajoutait une cinquième forme, le compilateur signalerait tous les endroits à compléter.
- **`MAX_DEPTH = 5`** : limite l'imbrication, pour qu'une condition mal construite ne fasse pas tourner la base indéfiniment.
- **`coalesce(…, false)`** : en SQL, comparer une cellule vide donne « inconnu », ni vrai ni faux. On le transforme en « faux » : une ligne dont l'âge est vide ne remplit pas « âge ≥ 18 ». Sans cela, `NOT (…)` donnerait aussi « inconnu » et les lignes disparaîtraient des comptes sans que personne ne le voie.
- **`as: number` / `as: date`** : « 9 » est plus petit que « 10 » en nombre, mais plus grand en texte (comparaison caractère par caractère). Le type dit comment comparer.
- **`nullif(…, 0)`** dans les calculs : une division par zéro donne NULL au lieu d'arrêter toute l'analyse ; la ligne est alors en anomalie COH-03, ce qui est le bon résultat.

### 16.4 — Complétude : CMP-02, CMP-03, CMP-04

Tous ces fichiers vont dans `rules/evaluators/`. Chaque règle suit le modèle de CMP-01 : un code, une dimension, ses paramètres, et la condition SQL qui repère une anomalie.

`Cmp02PlaceholderValueEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/** CMP-02 — Valeur bouche-trou interdite (N/A, 0, XXX, 01/01/1900…) : une valeur qui remplit la case sans rien dire. */
@Component
public class Cmp02PlaceholderValueEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "CMP-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.COMPLETENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("values", ParamType.TEXT_LIST, true, "param.placeholders"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var values = RuleParams.of(rule).textList("values");
        String value = normalizedValue(data, rule);
        String list = Sql.placeholders(values.size(), rule.normalization()::apply);
        return new SqlFragment(value + " IN (" + list + ")", new ArrayList<>(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La colonne « " + rule.firstColumn() + " » contient une valeur de remplissage, pas une vraie valeur.";
    }
}
```

`Cmp03ConditionalRequiredEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.rules.expression.ConditionCompiler;
import ci.cnam.dqanalyzer.rules.expression.ConditionParser;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/**
 * CMP-03 — Obligatoire sous condition : « si statut = Ayant droit, le n° d'assuré principal est renseigné ».
 * Seules les lignes qui remplissent la condition sont contrôlées.
 */
@Component
public class Cmp03ConditionalRequiredEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "CMP-03";
    }

    @Override
    public Dimension dimension() {
        return Dimension.COMPLETENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("when", ParamType.EXPRESSION, true, "param.when"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        var condition = ConditionParser.parse(RuleParams.of(rule).object("when"));
        return ConditionCompiler.compile(condition, data);
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        return Sql.empty(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La colonne « " + rule.firstColumn() + " » doit être renseignée pour cette ligne.";
    }
}
```

`Cmp04FillRateEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/**
 * CMP-04 — Taux de remplissage minimal : « téléphone renseigné sur au moins 80 % des lignes ».
 * Le score de la règle EST le taux de remplissage ; le pourcentage minimal est le seuil de la règle.
 * Contrairement à CMP-01, une cellule vide n'est pas une faute en soi : c'est leur proportion qui compte.
 */
@Component
public class Cmp04FillRateEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "CMP-04";
    }

    @Override
    public Dimension dimension() {
        return Dimension.COMPLETENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn();
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        return Sql.empty(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "Cellule vide dans « " + rule.firstColumn() + " » (le taux de remplissage attendu est de "
                + rule.thresholdPercent() + " %).";
    }
}
```

**Explication.**

- **CMP-02** : la liste des valeurs interdites passe par la même normalisation que la colonne (`Sql.placeholders(…, rule.normalization()::apply)`). Avec « majuscules » cochée, « n/a » dans le fichier et « N/A » dans la règle sont reconnus comme identiques.
- **CMP-03** : la seule différence avec CMP-01 est `scope` : on ne contrôle que les lignes qui remplissent la condition, compilée par `ConditionCompiler`. Les autres lignes ne comptent ni comme conformes ni comme anomalies.
- **CMP-04** : même condition SQL que CMP-01, mais un sens différent. CMP-01 dit « chaque ligne doit être remplie » (seuil souvent 100 %) ; CMP-04 dit « au moins 80 % des lignes » : le seuil de la règle porte le pourcentage du CDC, et le score de la règle est exactement le taux de remplissage. Pas besoin d'un calcul à part.

### 16.5 — Validité : VAL-01 à VAL-07

`Val01DataTypeEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** VAL-01 — Type de donnée : entier, décimal, date, booléen ou texte. */
@Component
public class Val01DataTypeEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("dataType", ParamType.TEXT, true, "param.dataType"),
                new Param("dateFormat", ParamType.TEXT, false, "param.dateFormat"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String column = data.quotedColumn(rule.firstColumn());
        String text = "trim(CAST(" + column + " AS VARCHAR))";
        return switch (params.text("dataType").toLowerCase(Locale.ROOT)) {
            case "entier", "integer" -> SqlFragment.of("TRY_CAST(" + text + " AS BIGINT) IS NULL");
            case "decimal", "décimal" -> SqlFragment.of(Sql.number(column) + " IS NULL");
            case "date" -> SqlFragment.of(Sql.date(column) + " IS NULL",
                    DateFormats.toDuckDb(params.textOrDefault("dateFormat", DateFormats.DEFAULT)));
            case "booleen", "booléen", "boolean" -> SqlFragment.of(
                    "lower(" + text + ") NOT IN ('true', 'false', 'vrai', 'faux', 'oui', 'non', 'o', 'n', '1', '0')");
            case "texte", "text" -> SqlFragment.of("FALSE");
            default -> throw new RuleEvaluationException(
                    "Règle " + rule.ruleCode() + " : type inconnu (entier, décimal, date, booléen ou texte)");
        };
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » n'est pas du type attendu ("
                + RuleParams.of(rule).text("dataType") + ").";
    }
}
```

`Val02PatternEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * VAL-02 — Format par expression régulière, ou par motif prédéfini (CDC §6). La valeur doit correspondre
 * au motif en entier, pas seulement en partie. Syntaxe des expressions : RE2 (celle de DuckDB).
 */
@Component
public class Val02PatternEvaluator extends FilledValueEvaluator {

    /** Motifs prédéfinis. Les formats des identifiants CNAM et CNPS seront ajoutés quand le métier les aura fournis. */
    static final Map<String, String> PRESETS = Map.of(
            "email", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
            "telephone_ci", "(\\+225|00225)?(01|05|07|21|25|27)[0-9]{8}",
            "date_jjmmaaaa", "(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[0-2])/[0-9]{4}",
            "code_postal", "[0-9]{2,5}",
            "alphanum_majuscule", "[A-Z0-9]+");

    @Override
    public String type() {
        return "VAL-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("preset", ParamType.TEXT, false, "param.preset"),
                new Param("regex", ParamType.TEXT, false, "param.regex"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String pattern = params.optionalText("preset")
                .map(name -> {
                    String preset = PRESETS.get(name);
                    if (preset == null) {
                        throw new RuleEvaluationException("Règle " + rule.ruleCode() + " : motif inconnu « " + name
                                + " » (motifs : " + String.join(", ", PRESETS.keySet()) + ")");
                    }
                    return preset;
                })
                .orElseGet(() -> params.text("regex"));
        return SqlFragment.of("NOT regexp_full_match(" + normalizedValue(data, rule) + ", ?)", pattern);
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » ne respecte pas le format attendu.";
    }
}
```

`Val03LengthEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** VAL-03 — Longueur comprise entre un minimum et un maximum (l'un des deux peut manquer). */
@Component
public class Val03LengthEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-03";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("min", ParamType.INTEGER, false, "param.minLength"),
                new Param("max", ParamType.INTEGER, false, "param.maxLength"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        var min = params.optionalNumber("min");
        var max = params.optionalNumber("max");
        if (min.isEmpty() && max.isEmpty()) {
            throw new RuleEvaluationException("Règle " + rule.ruleCode() + " : indique une longueur minimale ou maximale");
        }
        String length = "length(" + normalizedValue(data, rule) + ")";
        var parts = new ArrayList<String>();
        var values = new ArrayList<Object>();
        min.ifPresent(m -> {
            parts.add(length + " < ?");
            values.add(m.longValue());
        });
        max.ifPresent(m -> {
            parts.add(length + " > ?");
            values.add(m.longValue());
        });
        return new SqlFragment(String.join(" OR ", parts), List.copyOf(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La longueur de « " + rule.firstColumn() + " » n'est pas celle attendue.";
    }
}
```

`Val04RangeEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** VAL-04 — Plage de valeurs numériques : « âge entre 0 et 120 ». Une valeur non numérique est hors plage. */
@Component
public class Val04RangeEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-04";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("min", ParamType.DECIMAL, false, "param.min"),
                new Param("max", ParamType.DECIMAL, false, "param.max"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        var min = params.optionalNumber("min");
        var max = params.optionalNumber("max");
        if (min.isEmpty() && max.isEmpty()) {
            throw new RuleEvaluationException("Règle " + rule.ruleCode() + " : indique un minimum ou un maximum");
        }
        String number = Sql.number(data.quotedColumn(rule.firstColumn()));
        var parts = new ArrayList<String>();
        parts.add(number + " IS NULL");
        var values = new ArrayList<Object>();
        min.ifPresent(m -> {
            parts.add(number + " < ?");
            values.add(m);
        });
        max.ifPresent(m -> {
            parts.add(number + " > ?");
            values.add(m);
        });
        return new SqlFragment(String.join(" OR ", parts), List.copyOf(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » est hors de la plage autorisée.";
    }
}
```

`Val05AllowedValuesEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/** VAL-05 — Liste de valeurs autorisées : « sexe ∈ {M, F} ». La normalisation s'applique aux deux côtés. */
@Component
public class Val05AllowedValuesEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-05";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("values", ParamType.TEXT_LIST, true, "param.allowedValues"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var values = RuleParams.of(rule).textList("values");
        String list = Sql.placeholders(values.size(), rule.normalization()::apply);
        return new SqlFragment(normalizedValue(data, rule) + " NOT IN (" + list + ")", new ArrayList<>(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » ne fait pas partie des valeurs autorisées ("
                + String.join(", ", RuleParams.of(rule).textList("values")) + ").";
    }
}
```

`Val06DateFormatEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * VAL-06 — Format de date : la valeur doit être une date réelle dans l'un des formats acceptés.
 * « 31/02/2024 » est refusé : try_strptime vérifie que la date existe, pas seulement sa forme.
 */
@Component
public class Val06DateFormatEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "VAL-06";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("formats", ParamType.TEXT_LIST, true, "param.dateFormats"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        List<String> formats = RuleParams.of(rule).textList("formats").stream()
                .map(DateFormats::toDuckDb)
                .toList();
        String text = "trim(CAST(" + data.quotedColumn(rule.firstColumn()) + " AS VARCHAR))";
        String sql = String.join(" AND ", Collections.nCopies(formats.size(), "try_strptime(" + text + ", ?) IS NULL"));
        return new SqlFragment(sql, List.copyOf(formats));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » ne contient pas une date valide au format attendu ("
                + String.join(" ou ", RuleParams.of(rule).textList("formats")) + ").";
    }
}
```

`Val07ForbiddenCharactersEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Map;
import org.springframework.stereotype.Component;

/** VAL-07 — Caractères interdits : « pas de chiffres dans un nom ». */
@Component
public class Val07ForbiddenCharactersEvaluator extends FilledValueEvaluator {

    /** Raccourcis pour les cas fréquents ; sinon l'analyste saisit les caractères eux-mêmes. */
    private static final Map<String, String> PRESETS =
            Map.of("chiffres", "[0-9]", "speciaux", "[@#$%&*!?<>{}\\[\\]|\\\\^~]");

    @Override
    public String type() {
        return "VAL-07";
    }

    @Override
    public Dimension dimension() {
        return Dimension.VALIDITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("characters", ParamType.TEXT, true, "param.forbiddenCharacters"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        String characters = RuleParams.of(rule).text("characters");
        String pattern = PRESETS.getOrDefault(characters, characterClass(characters));
        return SqlFragment.of("regexp_matches(CAST(" + data.quotedColumn(rule.firstColumn()) + " AS VARCHAR), ?)", pattern);
    }

    /** « 0123 » → « [0123] », en protégeant les caractères qui ont un sens dans une classe RE2. */
    static String characterClass(String characters) {
        var sb = new StringBuilder("[");
        characters.codePoints().forEach(cp -> {
            if ("\\]^-[".indexOf(cp) >= 0) {
                sb.append('\\');
            }
            sb.appendCodePoint(cp);
        });
        return sb.append(']').toString();
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » contient un caractère interdit.";
    }
}
```

**Explication.**

- **VAL-01** : `TRY_CAST(… AS BIGINT)` renvoie NULL si la valeur n'est pas un entier ; c'est ce NULL qui signale l'anomalie. Le type « texte » ne produit jamais d'anomalie (`FALSE`) : il sert à documenter la colonne.
- **VAL-02** : `regexp_full_match` exige que toute la valeur corresponde au motif ; `regexp_matches` (utilisé par VAL-07) cherche seulement un morceau. Le motif téléphone accepte les numéros ivoiriens à 10 chiffres commençant par 01, 05, 07, 21, 25 ou 27, avec ou sans +225 devant. Les motifs des identifiants CNAM et CNPS s'ajouteront à `PRESETS` quand le métier aura donné leurs formats.
- **VAL-03 et VAL-04** : le minimum et le maximum sont facultatifs l'un et l'autre, mais il en faut au moins un. Dans VAL-04, une valeur qui n'est pas un nombre est en anomalie : « abc » n'est pas un âge entre 0 et 120.
- **VAL-05** : « M; F » tapé dans le formulaire suffit, `RuleParams.textList` découpe la liste.
- **VAL-06** : plusieurs formats possibles (« jj/mm/aaaa » ou « aaaa-mm-jj ») ; la valeur est en anomalie si elle ne correspond à aucun.
- **VAL-07** : `characterClass` protège les caractères spéciaux des expressions régulières (`]`, `^`, `-`, `[`, `\`). Sans cela, interdire « \]-# » produirait une expression invalide et la règle échouerait.

### 16.6 — Exactitude et intégrité : EXA-01, EXA-02, INT-01

`Exa01ReferenceMatchEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EXA-01 — Concordance avec une table de référence : « la date de naissance est celle de la base des assurés ».
 * Pour chaque ligne dont la clé existe dans la référence, la valeur comparée doit être identique.
 * Une clé absente de la référence n'est pas en anomalie ici : c'est le rôle d'INT-01.
 *
 * <p>Colonne 1 = la clé, colonne 2 = la valeur comparée. Paramètres : referenceTableId, referenceKey, referenceValue.
 */
@Component
public class Exa01ReferenceMatchEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "EXA-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.ACCURACY;
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(ParamSchema.ColumnArity.TWO, List.of(
                new Param("referenceTableId", ParamType.REFERENCE_TABLE, true, "param.referenceTable"),
                new Param("referenceKey", ParamType.TEXT, true, "param.referenceKey"),
                new Param("referenceValue", ParamType.TEXT, true, "param.referenceValue")));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return Sql.filled(data.quotedColumn(rule.firstColumn()));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String ref = DuckDbIdentifiers.quote(refs.duckDbTable(params.longValue("referenceTableId")));
        String refKey = "r." + DuckDbIdentifiers.quote(params.text("referenceKey"));
        String refValue = "r." + DuckDbIdentifiers.quote(params.text("referenceValue"));
        String key = data.quotedTable() + "." + data.quotedColumn(rule.firstColumn());
        String value = data.quotedTable() + "." + data.quotedColumn(rule.secondColumn());
        var norm = rule.normalization();
        String sameKey = norm.apply(refKey) + " = " + norm.apply(key);
        return SqlFragment.of("EXISTS (SELECT 1 FROM " + ref + " r WHERE " + sameKey + ")"
                + " AND NOT EXISTS (SELECT 1 FROM " + ref + " r WHERE " + sameKey
                + " AND " + norm.apply(refValue) + " IS NOT DISTINCT FROM " + norm.apply(value) + ")");
    }

    @Override
    protected String anomalyColumn(RuleDefinition rule) {
        return rule.secondColumn();
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.secondColumn() + " » ne correspond pas à la valeur de la table de référence.";
    }
}
```

`Exa02CheckDigitEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * EXA-02 — Clé de contrôle d'un identifiant.
 * <ul>
 *   <li>luhn : le dernier chiffre est la clé de Luhn (cartes, nombreux identifiants) ;</li>
 *   <li>mod97 : les 2 derniers chiffres valent 97 − (nombre qui précède modulo 97), comme le NIR français.</li>
 * </ul>
 * L'algorithme exact des identifiants CNAM / CNPS est à confirmer avec le métier (question ouverte du CDC).
 */
@Component
public class Exa02CheckDigitEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "EXA-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.ACCURACY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("algorithm", ParamType.TEXT, true, "param.algorithm"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        // Les espaces sont retirés avant le calcul : « 1 85 01 … » et « 18501… » sont le même identifiant
        String v = "replace(trim(CAST(" + data.quotedColumn(rule.firstColumn()) + " AS VARCHAR)), ' ', '')";
        String digitsOnly = "NOT regexp_full_match(" + v + ", '[0-9]{2,}')";
        return switch (RuleParams.of(rule).text("algorithm").toLowerCase(Locale.ROOT)) {
            case "luhn" -> SqlFragment.of(digitsOnly + " OR list_sum(list_transform(range(1, length(" + v + ") + 1),"
                    + " lambda i: CASE WHEN i % 2 = 0"
                    + " THEN (TRY_CAST(substr(reverse(" + v + "), i, 1) AS INTEGER) * 2) % 9"
                    + " + CASE WHEN substr(reverse(" + v + "), i, 1) = '9' THEN 9 ELSE 0 END"
                    + " ELSE TRY_CAST(substr(reverse(" + v + "), i, 1) AS INTEGER) END)) % 10 <> 0");
            case "mod97" -> SqlFragment.of(digitsOnly + " OR length(" + v + ") < 3 OR"
                    + " 97 - (TRY_CAST(left(" + v + ", length(" + v + ") - 2) AS HUGEINT) % 97)"
                    + " <> TRY_CAST(right(" + v + ", 2) AS INTEGER)");
            default -> throw new RuleEvaluationException(
                    "Règle " + rule.ruleCode() + " : algorithme inconnu (luhn ou mod97)");
        };
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La clé de contrôle de « " + rule.firstColumn() + " » est incorrecte.";
    }
}
```

`Int01ReferenceExistsEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/** INT-01 — Existence dans une table de référence : « le code commune existe au référentiel des communes ». */
@Component
public class Int01ReferenceExistsEvaluator extends FilledValueEvaluator {

    @Override
    public String type() {
        return "INT-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.REFERENTIAL_INTEGRITY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("referenceTableId", ParamType.REFERENCE_TABLE, true, "param.referenceTable"),
                new Param("referenceColumn", ParamType.TEXT, true, "param.referenceColumn"));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String ref = DuckDbIdentifiers.quote(refs.duckDbTable(params.longValue("referenceTableId")));
        String refColumn = "r." + DuckDbIdentifiers.quote(params.text("referenceColumn"));
        String value = data.quotedTable() + "." + data.quotedColumn(rule.firstColumn());
        var norm = rule.normalization();
        return SqlFragment.of("NOT EXISTS (SELECT 1 FROM " + ref + " r WHERE "
                + norm.apply(refColumn) + " = " + norm.apply(value) + ")");
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La valeur de « " + rule.firstColumn() + " » n'existe pas dans la table de référence.";
    }
}
```

**Explication.**

- **`EXISTS (SELECT 1 FROM ref r WHERE …)`** : une sous-requête qui, pour chaque ligne du fichier, cherche la ligne correspondante dans la table de référence. DuckDB la transforme en jointure : 100 000 lignes face à un référentiel de 1 000 communes se comparent en une fraction de seconde.
- **`"ds"."code_commune"`** (le nom de table devant la colonne) : dans la sous-requête, `"code_commune"` seul pourrait désigner une colonne de la référence portant le même nom. Préfixer par la table du fichier lève toute ambiguïté.
- **`IS NOT DISTINCT FROM`** : comme `=`, mais deux valeurs absentes sont considérées égales, et une valeur absente face à une valeur présente est différente. Un simple `=` donnerait « inconnu » et laisserait passer l'écart.
- **EXA-01 / INT-01** se complètent : INT-01 dit « ce code existe-t-il ? », EXA-01 dit « pour un code qui existe, la valeur associée est-elle la bonne ? ». Une ligne n'est donc jamais comptée deux fois pour la même raison.
- **EXA-02** : l'algorithme de Luhn double un chiffre sur deux en partant de la droite, puis vérifie que la somme est un multiple de 10. `(d × 2) % 9`, plus 9 quand d vaut 9, est une écriture courte de « doubler, puis retirer 9 si le résultat dépasse 9 ». `TRY_CAST` évite qu'une valeur contenant une lettre arrête la requête : elle est simplement en anomalie. **À valider avec le métier** : l'algorithme réel de la clé des identifiants CNAM et CNPS.

### 16.7 — Cohérence et actualité : COH-01 à COH-03, ACT-01, ACT-02

`Coh01ColumnComparisonEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * COH-01 — Comparaison entre deux colonnes d'une même ligne : « date de début ≤ date de fin ».
 * Contrôlé seulement quand les deux cellules sont remplies. Une valeur qu'on ne peut pas convertir
 * (date mal écrite, texte au lieu d'un nombre) est en anomalie.
 */
@Component
public class Coh01ColumnComparisonEvaluator extends RowPredicateEvaluator {

    private static final Map<String, String> OPERATORS =
            Map.of("lt", "<", "lte", "<=", "eq", "=", "ne", "<>", "gt", ">", "gte", ">=");

    @Override
    public String type() {
        return "COH-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.CONSISTENCY;
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(ParamSchema.ColumnArity.TWO, List.of(
                new Param("operator", ParamType.TEXT, true, "param.operator"),
                new Param("valueType", ParamType.TEXT, true, "param.valueType"),
                new Param("dateFormat", ParamType.TEXT, false, "param.dateFormat")));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return Sql.filled(data.quotedColumn(rule.firstColumn()))
                .and(Sql.filled(data.quotedColumn(rule.secondColumn())));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        String operator = OPERATORS.get(params.text("operator").toLowerCase(Locale.ROOT));
        if (operator == null) {
            throw new RuleEvaluationException(
                    "Règle " + rule.ruleCode() + " : opérateur inconnu (lt, lte, eq, ne, gt, gte)");
        }
        String a = data.quotedColumn(rule.firstColumn());
        String b = data.quotedColumn(rule.secondColumn());
        var values = new ArrayList<Object>();
        String left;
        String right;
        switch (params.text("valueType").toLowerCase(Locale.ROOT)) {
            case "number", "nombre" -> {
                left = Sql.number(a);
                right = Sql.number(b);
            }
            case "date" -> {
                String format = DateFormats.toDuckDb(params.textOrDefault("dateFormat", DateFormats.DEFAULT));
                left = Sql.date(a);
                right = Sql.date(b);
                values.add(format);
                values.add(format);
            }
            case "text", "texte" -> {
                left = rule.normalization().apply(a);
                right = rule.normalization().apply(b);
            }
            default -> throw new RuleEvaluationException(
                    "Règle " + rule.ruleCode() + " : type inconnu (number, date ou text)");
        }
        return new SqlFragment("NOT coalesce(" + left + " " + operator + " " + right + ", false)", values);
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » et « " + rule.secondColumn() + " » sont incohérentes entre elles.";
    }
}
```

`Coh02IfThenEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.rules.expression.ConditionCompiler;
import ci.cnam.dqanalyzer.rules.expression.ConditionParser;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * COH-02 — Règle conditionnelle SI… ALORS : « si sexe = M, pas de prestation maternité ».
 * Lignes contrôlées = celles qui remplissent le SI ; anomalie = le ALORS est faux.
 * La colonne indiquée sert seulement à afficher la valeur dans le rapport.
 */
@Component
public class Coh02IfThenEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "COH-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.CONSISTENCY;
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(ParamSchema.ColumnArity.ONE, List.of(
                new Param("when", ParamType.EXPRESSION, true, "param.when"),
                new Param("then", ParamType.EXPRESSION, true, "param.then")));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return ConditionCompiler.compile(ConditionParser.parse(RuleParams.of(rule).object("when")), data);
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var then = ConditionCompiler.compile(ConditionParser.parse(RuleParams.of(rule).object("then")), data);
        return new SqlFragment("NOT (" + then.sql() + ")", then.params());
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La ligne ne respecte pas la règle « " + rule.name() + " ».";
    }
}
```

`Coh03ComputedValueEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.rules.expression.NumericExpression;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * COH-03 — Valeur calculée : « total = quantité × prix unitaire », à une tolérance près (0,01 par défaut)
 * pour absorber les arrondis. Contrôlé quand la cible et toutes les colonnes du calcul sont remplies.
 */
@Component
public class Coh03ComputedValueEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "COH-03";
    }

    @Override
    public Dimension dimension() {
        return Dimension.CONSISTENCY;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("expression", ParamType.EXPRESSION, true, "param.expression"),
                new Param("tolerance", ParamType.DECIMAL, false, "param.tolerance"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        var scope = Sql.filled(data.quotedColumn(rule.firstColumn()));
        for (String column : NumericExpression.columns(RuleParams.of(rule).object("expression"))) {
            scope = scope.and(Sql.filled(data.quotedColumn(column)));
        }
        return scope;
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        var expression = NumericExpression.compile(params.object("expression"), data);
        String target = Sql.number(data.quotedColumn(rule.firstColumn()));
        var values = new ArrayList<Object>(expression.params());
        values.add(params.optionalNumber("tolerance").orElse(0.01));
        return new SqlFragment("coalesce(abs(" + target + " - " + expression.sql() + ") > ?, true)", List.copyOf(values));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » ne correspond pas au calcul attendu.";
    }
}
```

`Act01MaxAgeEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * ACT-01 — Âge maximal de la donnée : « dernière mise à jour de moins de 12 mois ».
 * Ne contrôle que les dates lisibles : une date mal écrite relève de VAL-06.
 * La date du jour vient d'une {@link Clock} : les tests la fixent, le résultat ne change pas d'un jour à l'autre.
 */
@Component
public class Act01MaxAgeEvaluator extends RowPredicateEvaluator {

    private final Clock clock;

    public Act01MaxAgeEvaluator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return "ACT-01";
    }

    @Override
    public Dimension dimension() {
        return Dimension.TIMELINESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(
                new Param("maxAge", ParamType.INTEGER, true, "param.maxAge"),
                new Param("unit", ParamType.TEXT, true, "param.ageUnit"),
                new Param("dateFormat", ParamType.TEXT, false, "param.dateFormat"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return SqlFragment.of(Sql.date(data.quotedColumn(rule.firstColumn())) + " IS NOT NULL", format(rule));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        var params = RuleParams.of(rule);
        long maxAge = params.longValue("maxAge");
        LocalDate today = LocalDate.now(clock);
        LocalDate limit = switch (params.text("unit").toLowerCase()) {
            case "jours", "days" -> today.minusDays(maxAge);
            case "mois", "months" -> today.minusMonths(maxAge);
            case "ans", "years" -> today.minusYears(maxAge);
            default -> throw new RuleEvaluationException("Règle " + rule.ruleCode() + " : unité inconnue (jours, mois, ans)");
        };
        return new SqlFragment(Sql.date(data.quotedColumn(rule.firstColumn())) + " < CAST(? AS DATE)",
                List.of(format(rule), limit.toString()));
    }

    private static String format(RuleDefinition rule) {
        return DateFormats.toDuckDb(RuleParams.of(rule).textOrDefault("dateFormat", DateFormats.DEFAULT));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        var params = RuleParams.of(rule);
        return "« " + rule.firstColumn() + " » date de plus de " + params.longValue("maxAge") + " " + params.text("unit") + ".";
    }
}
```

`Act02NotInFutureEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.DateFormats;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/** ACT-02 — Date non future : « pas de date de naissance dans le futur ». */
@Component
public class Act02NotInFutureEvaluator extends RowPredicateEvaluator {

    private final Clock clock;

    public Act02NotInFutureEvaluator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String type() {
        return "ACT-02";
    }

    @Override
    public Dimension dimension() {
        return Dimension.TIMELINESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn(new Param("dateFormat", ParamType.TEXT, false, "param.dateFormat"));
    }

    @Override
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return SqlFragment.of(Sql.date(data.quotedColumn(rule.firstColumn())) + " IS NOT NULL", format(rule));
    }

    @Override
    protected SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs) {
        return new SqlFragment(Sql.date(data.quotedColumn(rule.firstColumn())) + " > CAST(? AS DATE)",
                List.of(format(rule), LocalDate.now(clock).toString()));
    }

    private static String format(RuleDefinition rule) {
        return DateFormats.toDuckDb(RuleParams.of(rule).textOrDefault("dateFormat", DateFormats.DEFAULT));
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "« " + rule.firstColumn() + " » contient une date dans le futur.";
    }
}
```

**Explication.**

- **COH-01** : `NOT coalesce(a <= b, false)` est en anomalie si la comparaison est fausse **ou** impossible (« x » n'est pas une date). Les lignes où l'une des deux cellules est vide ne sont pas contrôlées : une date de fin absente n'est pas incohérente, elle est incomplète (CMP-01).
- **COH-02** : le SI passe dans `scope` (quelles lignes sont concernées), le ALORS dans `violation` (ce qui doit être vrai). C'est la traduction directe de « lignes contrôlées = lignes concernées par la règle » du CDC §7.
- **COH-03** : la tolérance de 0,01 évite de signaler 21,000001 au lieu de 21 : les calculs sur nombres à virgule ne tombent jamais parfaitement juste.
- **ACT-01 / ACT-02** : la date limite (aujourd'hui moins 12 mois) est calculée en Java puis passée en paramètre. Le SQL reste simple et la date du jour vient de la `Clock`, figée dans les tests.
- **L'ordre des « ? »** : `RowPredicateEvaluator` lie d'abord les valeurs de `violation` puis celles de `scope` pour le comptage, et l'inverse pour la liste des anomalies, parce que les morceaux de SQL apparaissent dans cet ordre dans chaque requête. Chaque règle peut donc utiliser des « ? » dans les deux sans s'en préoccuper.

### 16.8 — Unicité : UNI-01 à UNI-04

Une règle d'unicité ne peut pas juger une ligne seule : elle compare chaque ligne à toutes les autres. Elle n'utilise donc pas `RowPredicateEvaluator` mais une base dédiée, qui regroupe les lignes par clé (`GROUP BY`).

`DuplicateGroupsEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluator;
import ci.cnam.dqanalyzer.rules.RuleResult;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Base des règles d'unicité exacte (UNI-01, UNI-02, UNI-03). Une règle d'ensemble : on ne peut pas juger
 * une ligne seule, il faut la comparer à toutes les autres. En SQL, c'est un regroupement (GROUP BY) sur la clé.
 *
 * <p>CDC §7 : une ligne est en anomalie si elle appartient à un groupe de doublons ; chaque groupe reçoit un
 * numéro (dans l'ordre de sa première ligne) pour que le rapport les regroupe (RAP-04).
 * Les lignes dont une colonne de la clé est vide ne sont pas contrôlées.
 */
abstract class DuplicateGroupsEvaluator implements RuleEvaluator {

    /** Faut-il appliquer la normalisation de la règle à la clé (UNI-03) ou comparer les valeurs exactes ? */
    protected abstract boolean normalizeKey();

    @Override
    public Dimension dimension() {
        return Dimension.UNIQUENESS;
    }

    @Override
    public final RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
        if (rule.targetColumns().isEmpty()) {
            throw new RuleEvaluationException("La règle " + rule.ruleCode() + " ne cible aucune colonne");
        }
        var keyParts = new ArrayList<String>();
        var filled = new ArrayList<String>();
        var display = new ArrayList<String>();
        for (String column : rule.targetColumns()) {
            String quoted = data.quotedColumn(column);
            String value = normalizeKey() ? rule.normalization().apply(quoted) : "trim(CAST(" + quoted + " AS VARCHAR))";
            keyParts.add(value);
            filled.add(Sql.filled(quoted).sql());
            display.add("CAST(" + quoted + " AS VARCHAR)");
        }
        String sql = """
                WITH keyed AS (
                    SELECT %s AS row_num, list_value(%s) AS dup_key, concat_ws(' | ', %s) AS shown
                    FROM %s WHERE %s
                ),
                groups AS (
                    SELECT dup_key, dense_rank() OVER (ORDER BY min(row_num)) AS group_id
                    FROM keyed GROUP BY dup_key HAVING count(*) > 1
                )
                SELECT k.row_num, k.shown, g.group_id,
                       (SELECT count(*) FROM keyed) AS checked,
                       (SELECT count(*) FROM groups) AS group_count
                FROM keyed k JOIN groups g USING (dup_key)
                ORDER BY g.group_id, k.row_num
                """.formatted(
                        Dataset.ROW_NUM_COLUMN,
                        String.join(", ", keyParts),
                        String.join(", ", display),
                        data.quotedTable(),
                        String.join(" AND ", filled.stream().map(f -> "(" + f + ")").toList()));
        String message = rule.message() == null || rule.message().isBlank()
                ? "Doublon : " + String.join(" + ", rule.targetColumns()) + " apparaît plusieurs fois."
                : rule.message();
        String column = String.join(" + ", rule.targetColumns());

        try (var st = data.connection().createStatement()) {
            st.setFetchSize(5_000);
            long duplicates = 0;
            long groups = 0;
            Long checked = null;
            try (var rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    checked = rs.getLong(4);
                    groups = rs.getLong(5);
                    duplicates++;
                    sink.accept(new Anomaly(rs.getLong(1), column, rs.getString(2), message, rs.getLong(3)));
                }
            }
            if (checked == null) {
                checked = countChecked(data, filled);
            }
            return new RuleResult(checked, checked - duplicates, groups);
        } catch (SQLException e) {
            throw new RuleEvaluationException("Échec de la règle " + rule.ruleCode() + " (" + type() + ")", e);
        }
    }

    private static long countChecked(Dataset data, List<String> filled) throws SQLException {
        String sql = "SELECT count(*) FROM " + data.quotedTable() + " WHERE "
                + String.join(" AND ", filled.stream().map(f -> "(" + f + ")").toList());
        try (var st = data.connection().createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
```

Les trois règles d'unicité exacte ne font que choisir le nombre de colonnes et la normalisation.

`Uni01UniqueValueEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.ParamSchema;
import org.springframework.stereotype.Component;

/** UNI-01 — Valeur unique sur une colonne : « un n° d'immatriculation n'apparaît qu'une fois ». */
@Component
public class Uni01UniqueValueEvaluator extends DuplicateGroupsEvaluator {

    @Override
    public String type() {
        return "UNI-01";
    }

    @Override
    public ParamSchema paramSchema() {
        return ParamSchema.singleColumn();
    }

    @Override
    protected boolean normalizeKey() {
        return false;
    }
}
```

`Uni02UniqueCompositeKeyEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.ParamSchema;
import java.util.List;
import org.springframework.stereotype.Component;

/** UNI-02 — Clé composée unique : « nom + prénoms + date de naissance uniques ». */
@Component
public class Uni02UniqueCompositeKeyEvaluator extends DuplicateGroupsEvaluator {

    @Override
    public String type() {
        return "UNI-02";
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(ParamSchema.ColumnArity.MANY, List.of());
    }

    @Override
    protected boolean normalizeKey() {
        return false;
    }
}
```

`Uni03NormalizedDuplicateEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.ParamSchema;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * UNI-03 — Doublons après normalisation : « KOUAMÉ Jean » = « kouame jean ». Coche les options de
 * normalisation de la règle (espaces, majuscules, accents).
 */
@Component
public class Uni03NormalizedDuplicateEvaluator extends DuplicateGroupsEvaluator {

    @Override
    public String type() {
        return "UNI-03";
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(ParamSchema.ColumnArity.MANY, List.of());
    }

    @Override
    protected boolean normalizeKey() {
        return true;
    }
}
```

`Uni04ApproximateDuplicateEvaluator.java`

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ParamSchema.Param;
import ci.cnam.dqanalyzer.rules.ParamSchema.ParamType;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluator;
import ci.cnam.dqanalyzer.rules.RuleParams;
import ci.cnam.dqanalyzer.rules.RuleResult;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * UNI-04 — Doublons approchés : « N'GUESSAN » / « NGUESSAN ». Compare les lignes deux à deux avec une mesure
 * de ressemblance (Jaro-Winkler de 0 à 1, ou Levenshtein = nombre de lettres à changer).
 *
 * <p>Comparer chaque ligne à toutes les autres coûte n² comparaisons (10 milliards pour 100 000 lignes) :
 * le paramètre {@code blockColumn} (par exemple la date de naissance) limite la comparaison aux lignes qui ont
 * la même valeur dans cette colonne. C'est la technique dite de « blocage », indispensable sur gros fichier.
 *
 * <p>Simplification assumée en V1 : le groupe d'une ligne est la première ligne qui lui ressemble ; si A ressemble
 * à B et B à C sans que A ressemble à C, on peut obtenir deux groupes au lieu d'un.
 */
@Component
public class Uni04ApproximateDuplicateEvaluator implements RuleEvaluator {

    @Override
    public String type() {
        return "UNI-04";
    }

    @Override
    public Dimension dimension() {
        return Dimension.UNIQUENESS;
    }

    @Override
    public ParamSchema paramSchema() {
        return new ParamSchema(ParamSchema.ColumnArity.MANY, List.of(
                new Param("algorithm", ParamType.TEXT, true, "param.similarityAlgorithm"),
                new Param("threshold", ParamType.DECIMAL, true, "param.similarityThreshold"),
                new Param("blockColumn", ParamType.COLUMN, false, "param.blockColumn")));
    }

    @Override
    public RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
        var params = RuleParams.of(rule);
        double threshold = params.number("threshold");
        String similar = switch (params.text("algorithm").toLowerCase(Locale.ROOT)) {
            case "jaro-winkler", "jaro_winkler", "jarowinkler" -> "jaro_winkler_similarity(a.txt, b.txt) >= ?";
            case "levenshtein" -> "levenshtein(a.txt, b.txt) <= ?";
            default -> throw new RuleEvaluationException(
                    "Règle " + rule.ruleCode() + " : algorithme inconnu (jaro-winkler ou levenshtein)");
        };

        var parts = new ArrayList<String>();
        var filled = new ArrayList<String>();
        for (String column : rule.targetColumns()) {
            String quoted = data.quotedColumn(column);
            parts.add(rule.normalization().apply(quoted));
            filled.add("(" + Sql.filled(quoted).sql() + ")");
        }
        if (parts.isEmpty()) {
            throw new RuleEvaluationException("La règle " + rule.ruleCode() + " ne cible aucune colonne");
        }
        String block = params.optionalText("blockColumn")
                .map(c -> "trim(CAST(" + data.quotedColumn(c) + " AS VARCHAR))")
                .orElse("''");

        String sql = """
                WITH k AS (
                    SELECT %s AS row_num, concat_ws(' ', %s) AS txt, %s AS blk
                    FROM %s WHERE %s
                ),
                pairs AS (
                    SELECT a.row_num AS ra, b.row_num AS rb
                    FROM k a JOIN k b ON a.blk = b.blk AND a.row_num < b.row_num
                    WHERE %s
                ),
                members AS (
                    SELECT ra AS row_num, ra AS first_row FROM pairs
                    UNION SELECT rb, ra FROM pairs
                ),
                grouped AS (
                    SELECT row_num, min(first_row) AS first_row FROM members GROUP BY row_num
                )
                SELECT g.row_num, k.txt, dense_rank() OVER (ORDER BY g.first_row) AS group_id,
                       (SELECT count(*) FROM k) AS checked
                FROM grouped g JOIN k USING (row_num)
                ORDER BY group_id, g.row_num
                """.formatted(
                        Dataset.ROW_NUM_COLUMN, String.join(", ", parts), block, data.quotedTable(),
                        String.join(" AND ", filled), similar);
        String column = String.join(" + ", rule.targetColumns());
        String message = rule.message() == null || rule.message().isBlank()
                ? "Doublon probable : valeur très proche d'une autre ligne du même groupe."
                : rule.message();

        try (var ps = data.connection().prepareStatement(sql)) {
            ps.setFetchSize(5_000);
            ps.setObject(1, threshold);
            long anomalies = 0;
            long maxGroup = 0;
            Long checked = null;
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    anomalies++;
                    maxGroup = Math.max(maxGroup, rs.getLong(3));
                    checked = rs.getLong(4);
                    sink.accept(new Anomaly(rs.getLong(1), column, rs.getString(2), message, rs.getLong(3)));
                }
            }
            if (checked == null) {
                try (var st = data.connection().createStatement();
                        var rs = st.executeQuery("SELECT count(*) FROM " + data.quotedTable() + " WHERE "
                                + String.join(" AND ", filled))) {
                    rs.next();
                    checked = rs.getLong(1);
                }
            }
            return new RuleResult(checked, checked - anomalies, maxGroup);
        } catch (SQLException e) {
            throw new RuleEvaluationException("Échec de la règle " + rule.ruleCode() + " (" + type() + ")", e);
        }
    }
}
```

**Explication de la requête d'unicité exacte**, étape par étape (les blocs `WITH` sont des tables intermédiaires nommées) :

1. **`keyed`** : pour chaque ligne dont la clé est remplie, son numéro, sa clé (`list_value(nom, date)` regroupe plusieurs colonnes en une seule valeur comparable) et le texte à afficher (« KOUAMÉ Jean | 15/03/1990 »).
2. **`groups`** : les clés qui apparaissent plus d'une fois (`HAVING count(*) > 1`). `dense_rank() OVER (ORDER BY min(row_num))` numérote les groupes 1, 2, 3… dans l'ordre de leur première ligne, ce qui donne un rapport lisible de haut en bas.
3. La requête finale rattache chaque ligne en double à son groupe (`JOIN … USING (dup_key)`), triée par groupe puis par ligne. Le rapport pourra afficher « Groupe 1 : lignes 2 et 3 » (RAP-04).

Le nombre de groupes est enregistré avec le résultat (`duplicateGroups`). Les « lignes en surplus » du CDC §7 s'en déduisent : nombre de lignes en anomalie − nombre de groupes.

**Explication de UNI-04.** `pairs` compare chaque ligne à celles du même bloc (`a.blk = b.blk`) et garde les paires assez ressemblantes ; `a.row_num < b.row_num` évite de comparer une ligne à elle-même et de compter chaque paire deux fois. `members` et `grouped` rattachent chaque ligne à la première ligne qui lui ressemble. Sans `blockColumn`, toutes les lignes sont dans le même bloc : à réserver aux fichiers de quelques milliers de lignes. Avec Jaro-Winkler, un seuil de 0,90 à 0,95 est un bon point de départ pour des noms ; à ajuster sur de vrais fichiers avec le métier.

### 16.9 — Le test commun des 23 règles

`src/test/resources/datasets/catalogue_regles.csv` : 5 lignes qui contiennent, à des endroits connus, au moins une anomalie pour chaque règle. Les guillemets protègent les espaces significatifs ; `\N` est une valeur absente.

```csv
id;nom;num_secu;statut;num_principal;sexe;age;tel;date_naiss;date_debut;date_fin;quantite;prix;total;code_commune;maj;carte;nir;email;commune
1;KOUAMÉ Jean;1850101234567;Assuré;;M;34;0707123456;15/03/1990;01/01/2024;31/12/2024;2;10,5;21;001;01/06/2026;4539578763621486;185017512345609;a@b.ci;Plateau
2;"kouame jean ";1850101234567;Ayant droit;;f;abc;123;31/02/1990;01/02/2024;01/01/2024;3;2;7;999;01/01/2020;4539578763621487;185017512345600;pas-un-mail;Treichville
3;N'GUESSAN Paul;;"  Ayant droit";A123;X;150;+2250506070809;01/01/1900;;;1;5;5;002;01/01/2030;12ab;;x@y.com;Abobo
4;NGUESSAN Paul;2900512345678;Conjoint;;M;17;0101020304;01/01/1900;05/05/2024;06/05/2024;;;;001;23/10/2025;;;;
5;TRAORE2 Awa;2900512345678;Ayant droit;B9;F;45;\N;15/03/2030;x;01/01/2024;2;2;4,001;003;2026-01-01;;;;yopougon
```

`src/test/java/ci/cnam/dqanalyzer/rules/evaluators/RuleCatalogueTest.java`

```java
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
                Arguments.of("CMP-01 vide", new Cmp01RequiredValueEvaluator(), List.of("num_secu"), Map.of(), NONE, 5, List.of(4L)),
                Arguments.of("CMP-02 bouche-trou", new Cmp02PlaceholderValueEvaluator(), List.of("date_naiss"),
                        Map.of("values", List.of("01/01/1900", "N/A", "0")), TRIM_UPPER, 5, List.of(4L, 5L)),
                Arguments.of("CMP-03 si ayant droit", new Cmp03ConditionalRequiredEvaluator(), List.of("num_principal"),
                        Map.of("when", Map.of("column", "statut", "op", "eq", "value", "Ayant droit")), NONE, 3, List.of(3L)),
                Arguments.of("CMP-04 remplissage", new Cmp04FillRateEvaluator(), List.of("tel"), Map.of(), NONE, 5, List.of(6L)),
                Arguments.of("VAL-01 entier", new Val01DataTypeEvaluator(), List.of("age"), Map.of("dataType", "entier"), NONE, 5, List.of(3L)),
                Arguments.of("VAL-01 date", new Val01DataTypeEvaluator(), List.of("date_naiss"), Map.of("dataType", "date"), NONE, 5, List.of(3L)),
                Arguments.of("VAL-02 téléphone CI", new Val02PatternEvaluator(), List.of("tel"), Map.of("preset", "telephone_ci"), NONE, 4, List.of(3L)),
                Arguments.of("VAL-02 e-mail", new Val02PatternEvaluator(), List.of("email"), Map.of("preset", "email"), NONE, 3, List.of(3L)),
                Arguments.of("VAL-03 longueur 13", new Val03LengthEvaluator(), List.of("num_secu"), Map.of("min", 13, "max", 13), NONE, 4, List.of()),
                Arguments.of("VAL-04 âge 0-120", new Val04RangeEvaluator(), List.of("age"), Map.of("min", 0, "max", 120), NONE, 5, List.of(3L, 4L)),
                Arguments.of("VAL-05 sexe", new Val05AllowedValuesEvaluator(), List.of("sexe"), Map.of("values", "M; F"), ALL, 5, List.of(4L)),
                Arguments.of("VAL-06 jj/mm/aaaa", new Val06DateFormatEvaluator(), List.of("date_naiss"),
                        Map.of("formats", List.of("jj/mm/aaaa")), NONE, 5, List.of(3L)),
                Arguments.of("VAL-07 chiffres", new Val07ForbiddenCharactersEvaluator(), List.of("nom"), Map.of("characters", "chiffres"), NONE, 5, List.of(6L)),
                Arguments.of("VAL-07 caractères spéciaux", new Val07ForbiddenCharactersEvaluator(), List.of("nom"), Map.of("characters", "]-#"), NONE, 5, List.of()),
                Arguments.of("EXA-01 commune", new Exa01ReferenceMatchEvaluator(), List.of("code_commune", "commune"),
                        Map.of("referenceTableId", 1, "referenceKey", "code", "referenceValue", "nom"), ALL, 5, List.of(4L, 5L)),
                Arguments.of("EXA-02 Luhn", new Exa02CheckDigitEvaluator(), List.of("carte"), Map.of("algorithm", "luhn"), NONE, 3, List.of(3L, 4L)),
                Arguments.of("EXA-02 modulo 97", new Exa02CheckDigitEvaluator(), List.of("nir"), Map.of("algorithm", "mod97"), NONE, 2, List.of(3L)),
                Arguments.of("COH-01 début ≤ fin", new Coh01ColumnComparisonEvaluator(), List.of("date_debut", "date_fin"),
                        Map.of("operator", "lte", "valueType", "date"), NONE, 4, List.of(3L, 6L)),
                Arguments.of("COH-02 si M alors pas maternité", new Coh02IfThenEvaluator(), List.of("statut"),
                        Map.of("when", Map.of("column", "sexe", "op", "eq", "value", "M"),
                                "then", Map.of("column", "statut", "op", "ne", "value", "Maternité")), NONE, 2, List.of()),
                Arguments.of("COH-03 total", new Coh03ComputedValueEvaluator(), List.of("total"),
                        Map.of("expression", Map.of("op", "mul", "args", List.of(Map.of("column", "quantite"), Map.of("column", "prix")))),
                        NONE, 4, List.of(3L)),
                Arguments.of("ACT-01 12 mois", new Act01MaxAgeEvaluator(TODAY), List.of("maj"), Map.of("maxAge", 12, "unit", "mois"), NONE, 4, List.of(3L)),
                Arguments.of("ACT-02 non future", new Act02NotInFutureEvaluator(TODAY), List.of("date_naiss"), Map.of(), NONE, 4, List.of(6L)),
                Arguments.of("INT-01 commune connue", new Int01ReferenceExistsEvaluator(), List.of("code_commune"),
                        Map.of("referenceTableId", 1, "referenceColumn", "code"), TRIM, 5, List.of(3L)),
                Arguments.of("UNI-01 n° unique", new Uni01UniqueValueEvaluator(), List.of("num_secu"), Map.of(), NONE, 4, List.of(2L, 3L, 5L, 6L)),
                Arguments.of("UNI-02 nom + date", new Uni02UniqueCompositeKeyEvaluator(), List.of("nom", "date_naiss"), Map.of(), NONE, 5, List.of()),
                Arguments.of("UNI-03 normalisé", new Uni03NormalizedDuplicateEvaluator(), List.of("nom"), Map.of(), ALL, 5, List.of(2L, 3L)),
                Arguments.of("UNI-04 approché", new Uni04ApproximateDuplicateEvaluator(), List.of("nom"),
                        Map.of("algorithm", "jaro-winkler", "threshold", 0.9, "blockColumn", "date_naiss"), ALL, 5, List.of(4L, 5L)),
                Arguments.of("CMP-03 condition composée", new Cmp03ConditionalRequiredEvaluator(), List.of("num_principal"),
                        Map.of("when", Map.of("all", List.of(
                                Map.of("column", "statut", "op", "in", "value", List.of("Ayant droit", "Conjoint")),
                                Map.of("column", "age", "op", "gte", "value", 18, "as", "number"),
                                Map.of("not", Map.of("column", "date_naiss", "op", "lt", "value", "01/01/1950", "as", "date"))))),
                        NONE, 1, List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void regle_trouve_les_anomalies_attendues(String name, RuleEvaluator evaluator, List<String> columns,
            Map<String, Object> params, Normalization normalization, int expectedChecked, List<Long> expectedRows) {
        var rule = new RuleDefinition(1L, "R-" + evaluator.type(), 1, name, evaluator.type(), evaluator.dimension(),
                columns, params, normalization, Severity.MAJOR, 95, null);
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, refs, anomalies::add);

        assertThat(result.checkedRows()).as("lignes contrôlées").isEqualTo(expectedChecked);
        assertThat(anomalies).extracting(Anomaly::rowNum).as("lignes en anomalie").containsExactlyElementsOf(expectedRows);
        assertThat(result.anomalyRows()).isEqualTo(expectedRows.size());
    }
}
```

**Explication.**

- **`@ParameterizedTest` + `@MethodSource("cases")`** : un seul test, exécuté 28 fois avec des données différentes, une par ligne de `cases()`. Chaque exécution porte son nom (« VAL-04 âge 0-120 ») : si une règle casse, le rapport dit laquelle.
- **`@TestInstance(PER_CLASS)` + `@BeforeAll`** : le CSV est chargé une seule fois pour les 28 cas, au lieu de 28 fois.
- **Les attendus** ont été obtenus en exécutant le SQL de chaque règle sur ce fichier avec DuckDB 2.0, puis vérifiés à la main. Exemple, VAL-04 sur `age` : la ligne 3 contient « abc » (pas un nombre) et la ligne 4 « 150 » (plus de 120) ; les 5 lignes sont remplies, donc 5 contrôlées et 2 anomalies.
- **Pour une nouvelle règle** (format d'identifiant CNAM par exemple) : ajoute une colonne et quelques valeurs au CSV, une ligne dans `cases()`, puis écris la règle jusqu'à ce que le test passe.

**Demande à Claude Code pour vérifier le lot :**

```text
J'ai ajouté les 22 évaluateurs du catalogue (étape 16 du guide). Lance ./mvnw spotless:apply verify.
Si un test de RuleCatalogueTest échoue, montre-moi le SQL généré par la règle et la ligne du CSV
concernée avant de corriger quoi que ce soit : je veux comprendre l'écart.
```

**Vérification de fin de lot.** `./mvnw verify` passe ; le rapport affiche les 28 cas de `RuleCatalogueTest` au vert. Critère de recette CDC couvert : « Les 23 types de règles fonctionnent sur un fichier de test à anomalies connues, et chaque anomalie attendue est détectée ».

## Étape 17 — Import des fichiers

Ce lot transforme un fichier reçu en base DuckDB prête pour les règles : contrôles de sécurité, conversion, chargement, numéro de ligne d'origine. Le code donné couvre CSV, Excel (.xlsx) et Parquet, les trois formats qui portent l'essentiel des fichiers du service ; les autres formats et l'envoi par morceaux se font ensuite avec Claude Code sur le même modèle. Branche conseillée : `feat/import`. Ces classes ont été compilées ; le SQL de chargement a été exécuté sur DuckDB 2.0 avec 300 000 lignes, et chaque ligne garde son bon numéro.

**Principe.** Chaque fichier importé reçoit sa propre base DuckDB, `storage/files/<id>.duckdb`, avec une table `raw` (les données, toutes en texte) et une vue `ds` qui ajoute `_row_num`. Tous les formats finissent dans cette forme : les règles ne savent pas d'où vient le fichier (CDC §5.1 bis).

### 17.1 — `shared/duckdb/DuckDbWorkspace.java`

```java
package ci.cnam.dqanalyzer.shared.duckdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ouvre les bases DuckDB de l'outil : une base (un fichier .duckdb) par fichier importé, rangée dans
 * {@code dq.storage.path/files/}. DuckDB travaille sur disque : un fichier de 2 Go ne passe jamais en mémoire Java.
 */
@Component
public class DuckDbWorkspace {

    /** Vue que lisent les règles : les données du fichier importé + la colonne « _row_num ». */
    public static final String DATASET_VIEW = "ds";

    private final Path root;
    private final String memoryLimit;

    public DuckDbWorkspace(
            @Value("${dq.storage.path}") Path storagePath, @Value("${dq.duckdb.memory-limit:2GB}") String memoryLimit) {
        this.root = storagePath.resolve("files");
        this.memoryLimit = memoryLimit;
    }

    /** Chemin de la base DuckDB d'un fichier importé. */
    public Path databaseFile(long sourceFileId) {
        return root.resolve(sourceFileId + ".duckdb");
    }

    /** Ouvre (ou crée) la base d'un fichier importé. Toujours dans un try-with-resources : la connexion se ferme. */
    public Connection open(long sourceFileId) throws SQLException {
        try {
            Files.createDirectories(root);
        } catch (java.io.IOException e) {
            throw new SQLException("Dossier de stockage inaccessible : " + root, e);
        }
        var props = new Properties();
        props.setProperty("memory_limit", memoryLimit);
        props.setProperty("temp_directory", root.resolve("tmp").toString());
        props.setProperty("preserve_insertion_order", "true");
        return DriverManager.getConnection("jdbc:duckdb:" + databaseFile(sourceFileId), props);
    }

    /** Colonnes du fichier importé, dans l'ordre, sans la colonne technique « _row_num ». */
    public List<String> columns(Connection connection) throws SQLException {
        var columns = new ArrayList<String>();
        try (var st = connection.createStatement();
                var rs = st.executeQuery(
                        "SELECT column_name FROM (DESCRIBE " + DATASET_VIEW + ") WHERE column_name <> '_row_num'")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }
}
```

**Explication.** `memory_limit` borne la mémoire que DuckDB s'autorise (2 Go par défaut, réglable) ; au-delà, il écrit ses calculs intermédiaires dans `temp_directory` au lieu de faire tomber l'application. `preserve_insertion_order` garantit que les lignes restent dans l'ordre du fichier, ce dont dépend le numéro de ligne. Ajoute dans `application.yml`, sous `dq:` :

```yaml
  duckdb:
    memory-limit: 2GB        # mémoire maximale par analyse ; DuckDB déborde sur disque au-delà
```

### 17.2 — Le contrat de l'import (`importing/`, racine du module)

`ImportFormat.java`

```java
package ci.cnam.dqanalyzer.importing;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Formats acceptés à l'import (CDC §5.1 bis), avec leurs extensions et les types réels admis. */
public enum ImportFormat {
    CSV(Set.of("csv", "tsv", "txt"), Set.of("text/plain", "text/csv", "text/tab-separated-values")),
    XLSX(Set.of("xlsx", "xlsm"), Set.of("application/x-tika-ooxml", "application/zip",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    PARQUET(Set.of("parquet"), Set.of("application/x-parquet", "application/octet-stream")),
    JSON(Set.of("json", "jsonl", "ndjson"), Set.of("application/json", "text/plain")),
    XML(Set.of("xml"), Set.of("application/xml", "text/xml")),
    FIXED_WIDTH(Set.of("dat"), Set.of("text/plain")),
    XLS(Set.of("xls"), Set.of("application/x-tika-msoffice", "application/vnd.ms-excel")),
    ODS(Set.of("ods"), Set.of("application/vnd.oasis.opendocument.spreadsheet", "application/zip"));

    private final Set<String> extensions;
    private final Set<String> mimeTypes;

    ImportFormat(Set<String> extensions, Set<String> mimeTypes) {
        this.extensions = extensions;
        this.mimeTypes = mimeTypes;
    }

    public static Optional<ImportFormat> fromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.extensions.contains(ext)).findFirst();
    }

    public boolean acceptsMimeType(String mimeType) {
        return mimeTypes.contains(mimeType);
    }
}
```

`ImportSettings.java`

```java
package ci.cnam.dqanalyzer.importing;

/**
 * Paramètres d'import choisis par l'analyste (IMP-02, IMP-03), enregistrables dans un modèle (IMP-08).
 *
 * @param sheetName feuille Excel (null = la première)
 * @param headerRow n° de la ligne d'en-tête dans le fichier, à partir de 1
 * @param delimiter séparateur d'un fichier texte : ";", "," ou "\t"
 * @param encoding "UTF-8" ou "windows-1252"
 */
public record ImportSettings(ImportFormat format, String sheetName, int headerRow, String delimiter, String encoding) {

    public ImportSettings {
        if (headerRow < 1) {
            throw new IllegalArgumentException("La ligne d'en-tête commence à 1");
        }
        delimiter = delimiter == null || delimiter.isEmpty() ? ";" : delimiter;
        encoding = encoding == null || encoding.isBlank() ? "UTF-8" : encoding;
    }

    public static ImportSettings defaults(ImportFormat format) {
        return new ImportSettings(format, null, 1, ";", "UTF-8");
    }
}
```

**Explication.** `ImportFormat` connaît pour chaque format ses extensions et les types réels admis : un fichier `.xlsx` doit vraiment être un classeur Excel, pas un exécutable renommé. `ImportSettings` regroupe ce que l'analyste choisit à l'écran (feuille, ligne d'en-tête, séparateur, encodage) ; il sera enregistré tel quel dans le modèle d'analyse (IMP-08), pour ne pas le ressaisir au fichier suivant.

### 17.3 — Contrôles de sécurité (`importing/domain/`)

`ImportRejectedException.java`

```java
package ci.cnam.dqanalyzer.importing.domain;

/** Fichier refusé à l'import. Le message est affiché tel quel à l'analyste. */
public class ImportRejectedException extends RuntimeException {

    public ImportRejectedException(String message) {
        super(message);
    }
}
```

`FileInspector.java`

```java
package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Contrôles de sécurité d'un fichier reçu (IMP-05, IMP-06, SEC-03) : taille, fichier vide, extension connue,
 * type réel cohérent avec l'extension, empreinte SHA-256.
 */
@Component
public class FileInspector {

    private final Tika tika = new Tika();
    private final DataSize maxSize;

    public FileInspector(@Value("${dq.upload.max-file-size}") DataSize maxSize) {
        this.maxSize = maxSize;
    }

    public record Inspection(ImportFormat format, String mimeType, long size, String sha256) {}

    public Inspection inspect(Path file, String originalName) throws IOException {
        long size = Files.size(file);
        if (size == 0) {
            throw new ImportRejectedException("Le fichier « " + originalName + " » est vide.");
        }
        if (size > maxSize.toBytes()) {
            throw new ImportRejectedException("Le fichier dépasse la taille maximale autorisée (" + maxSize.toMegabytes() + " Mo).");
        }
        var format = ImportFormat.fromFileName(originalName)
                .orElseThrow(() -> new ImportRejectedException("Format non pris en charge : « " + originalName + " »."));
        String mimeType = tika.detect(file);
        boolean parquet = format == ImportFormat.PARQUET && startsWith(file, "PAR1");
        if (!parquet && !format.acceptsMimeType(mimeType)) {
            throw new ImportRejectedException("Le contenu du fichier ne correspond pas à son extension (" + mimeType
                    + "). Le fichier est peut-être corrompu ou renommé.");
        }
        return new Inspection(format, mimeType, size, sha256(file));
    }

    private static boolean startsWith(Path file, String magic) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(magic.length()), java.nio.charset.StandardCharsets.US_ASCII).equals(magic);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

**Explication.**

- **`tika.detect(file)`** lit les premiers octets du fichier pour reconnaître son vrai type, au lieu de croire l'extension. Un fichier dont le contenu ne correspond pas à l'extension est refusé (SEC-03). Les fichiers Parquet se reconnaissent à leurs 4 premiers octets, « PAR1 ».
- **SHA-256** : une empreinte de 64 caractères, unique pour un contenu donné (IMP-06). Elle sert à prouver quel fichier exact a été analysé, et à reconnaître un fichier déjà importé. `DigestInputStream` calcule l'empreinte en lisant le fichier par blocs : un fichier de 2 Go ne passe pas en mémoire.
- **Les messages** sont destinés à l'analyste et ne révèlent rien du serveur.

### 17.4 — Excel vers CSV, ligne par ligne

`importing/domain/HeaderNames.java`

```java
package ci.cnam.dqanalyzer.importing.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Rend les noms de colonnes utilisables : vides → « colonne_3 », doublons → « nom_2 », espaces retirés. */
final class HeaderNames {

    private HeaderNames() {}

    static List<String> clean(List<String> raw) {
        var seen = new HashSet<String>();
        var result = new ArrayList<String>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            String name = raw.get(i) == null ? "" : raw.get(i).strip();
            if (name.isEmpty()) {
                name = "colonne_" + (i + 1);
            }
            if (name.equalsIgnoreCase("_row_num")) {
                name = name + "_fichier";
            }
            String unique = name;
            int n = 2;
            while (!seen.add(unique.toLowerCase(Locale.ROOT))) {
                unique = name + "_" + n++;
            }
            result.add(unique);
        }
        return result;
    }
}
```

`importing/domain/ExcelToCsvConverter.java`

```java
package ci.cnam.dqanalyzer.importing.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;

/**
 * Convertit une feuille Excel (.xlsx) en CSV UTF-8, ligne par ligne, sans charger le classeur en mémoire
 * (lecture SAX de POI). La première colonne du CSV est « _row_num » : le n° de la ligne dans Excel,
 * pour que les anomalies pointent la ligne que l'analyste voit dans son classeur.
 * Les valeurs sont écrites telles qu'Excel les affiche (dates au format de la cellule, nombres arrondis comme à l'écran).
 */
@Component
public class ExcelToCsvConverter {

    /** @return les noms de colonnes lus sur la ligne d'en-tête, dans l'ordre */
    public List<String> convert(Path xlsx, String sheetName, int headerRow, Path csvOut) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(xlsx.toFile(), PackageAccess.READ);
                Writer writer = Files.newBufferedWriter(csvOut, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.RFC4180)) {
            var strings = new ReadOnlySharedStringsTable(pkg);
            var reader = new XSSFReader(pkg);
            var styles = reader.getStylesTable();
            var sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheet = sheets.next()) {
                    if (sheetName == null || sheetName.equals(sheets.getSheetName())) {
                        var collector = new RowWriter(printer, headerRow);
                        var handler = new XSSFSheetXMLHandler(
                                styles, null, strings, collector, new DataFormatter(Locale.FRANCE), false);
                        var parser = XMLHelper.newXMLReader();
                        parser.setContentHandler(handler);
                        parser.parse(new InputSource(sheet));
                        if (collector.header == null) {
                            throw new ImportRejectedException("La ligne d'en-tête " + headerRow + " est vide ou absente.");
                        }
                        return collector.header;
                    }
                }
            }
            throw new ImportRejectedException("La feuille « " + sheetName + " » n'existe pas dans ce classeur.");
        } catch (ImportRejectedException e) {
            throw e;
        } catch (Exception e) {
            throw new ImportRejectedException("Le fichier Excel est illisible ou corrompu : " + e.getMessage());
        }
    }

    /** Reçoit les cellules de POI une par une et écrit une ligne CSV par ligne Excel. */
    private static final class RowWriter implements SheetContentsHandler {

        private final CSVPrinter printer;
        private final int headerRow;
        private final List<String> current = new ArrayList<>();
        private List<String> header;
        private int excelRow;

        RowWriter(CSVPrinter printer, int headerRow) {
            this.printer = printer;
            this.headerRow = headerRow;
        }

        @Override
        public void startRow(int rowNum) {
            excelRow = rowNum + 1; // POI compte à partir de 0, Excel à partir de 1
            current.clear();
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int col = new CellReference(cellReference).getCol();
            while (current.size() < col) {
                current.add(""); // cellules vides sautées par Excel
            }
            current.add(formattedValue == null ? "" : formattedValue);
        }

        @Override
        public void endRow(int rowNum) {
            try {
                if (excelRow < headerRow) {
                    return;
                }
                if (excelRow == headerRow) {
                    header = HeaderNames.clean(current);
                    var line = new ArrayList<String>();
                    line.add("_row_num");
                    line.addAll(header);
                    printer.printRecord(line);
                    return;
                }
                if (header == null) {
                    return;
                }
                var line = new ArrayList<String>(header.size() + 1);
                line.add(Integer.toString(excelRow));
                for (int i = 0; i < header.size(); i++) {
                    line.add(i < current.size() ? current.get(i) : "");
                }
                printer.printRecord(line);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // en-têtes et pieds de page d'impression : ignorés
        }
    }
}
```

**Explication.**

- **Lecture SAX** : au lieu d'ouvrir tout le classeur en mémoire (`XSSFWorkbook`, que le `CLAUDE.md` interdit), POI lit le fichier XML de la feuille au fil de l'eau et appelle `startRow`, `cell`, `endRow` pour chaque ligne. La mémoire utilisée ne dépend pas du nombre de lignes. Seule la table des textes partagés d'Excel est chargée ; elle reste raisonnable jusqu'à la limite d'Excel (1 048 576 lignes).
- **`DataFormatter(Locale.FRANCE)`** : écrit chaque valeur telle qu'Excel l'affiche en France (date « 15/03/1990 », décimale « 10,5 »). L'analyste retrouve dans le rapport exactement ce qu'il voit dans son classeur.
- **Cellules et lignes vides** : Excel n'enregistre pas les cellules vides. `CellReference("D7").getCol()` donne la colonne réelle (3), et le code comble les trous. Une ligne entièrement vide n'apparaît pas, mais chaque ligne écrite porte son vrai numéro Excel dans `_row_num` : les numéros restent justes.
- **`HeaderNames`** : un en-tête vide devient « colonne\_3 », un doublon « nom\_2 ». La comparaison ignore la casse, parce que DuckDB considère « Nom » et « nom » comme la même colonne.
- **Formats .xls et .ods** : le principe est le même (une classe de conversion vers ce CSV), mais avec un lecteur différent ; ils viennent dans la demande à Claude Code ci-dessous.

### 17.5 — Chargement dans DuckDB : `importing/infra/DuckDbLoader.java`

```java
package ci.cnam.dqanalyzer.importing.infra;

import ci.cnam.dqanalyzer.importing.ImportSettings;
import ci.cnam.dqanalyzer.importing.domain.ExcelToCsvConverter;
import ci.cnam.dqanalyzer.importing.domain.ImportRejectedException;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbWorkspace;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Charge un fichier importé dans sa base DuckDB : une table « raw » (les données, toutes en texte) et une vue « ds »
 * qui ajoute « _row_num », le n° de ligne dans le fichier d'origine. Les règles lisent la vue « ds ».
 *
 * <p>Tous les formats finissent en table : CSV et Parquet sont lus directement par DuckDB, en flux et en parallèle ;
 * Excel est d'abord converti en CSV par POI, ligne par ligne. Aucun format ne passe en entier en mémoire (PERF-03).
 */
@Component
public class DuckDbLoader {

    public static final String TABLE = "raw";
    public static final String VIEW = DuckDbWorkspace.DATASET_VIEW;

    private final DuckDbWorkspace workspace;
    private final ExcelToCsvConverter excel;

    public DuckDbLoader(DuckDbWorkspace workspace, ExcelToCsvConverter excel) {
        this.workspace = workspace;
        this.excel = excel;
    }

    public record LoadResult(long rowCount, List<String> columns) {}

    public LoadResult load(long sourceFileId, Path file, ImportSettings settings) throws IOException, SQLException {
        Path work = Files.createTempFile(file.getParent(), "import-", ".csv");
        try (Connection c = workspace.open(sourceFileId);
                var st = c.createStatement()) {
            switch (settings.format()) {
                case CSV -> {
                    Path utf8 = toUtf8(file, settings.encoding(), work);
                    try (var ps = c.prepareStatement("CREATE OR REPLACE TABLE " + TABLE + " AS SELECT * FROM read_csv(?,"
                            + " delim = ?, header = true, skip = ?, all_varchar = true, quote = '\"', escape = '\"',"
                            + " null_padding = true, strict_mode = false)")) {
                        ps.setString(1, utf8.toString());
                        ps.setString(2, settings.delimiter());
                        ps.setInt(3, settings.headerRow() - 1);
                        ps.execute();
                    }
                    // n° de ligne = position dans la table + n° de la ligne d'en-tête + 1
                    st.execute("CREATE OR REPLACE VIEW " + VIEW + " AS SELECT CAST(rowid AS BIGINT) + "
                            + (settings.headerRow() + 1) + " AS \"_row_num\", * FROM " + TABLE);
                }
                case XLSX -> {
                    excel.convert(file, settings.sheetName(), settings.headerRow(), work);
                    try (var ps = c.prepareStatement("CREATE OR REPLACE TABLE " + TABLE + " AS SELECT * FROM read_csv(?,"
                            + " delim = ',', header = true, all_varchar = true, quote = '\"', escape = '\"')")) {
                        ps.setString(1, work.toString());
                        ps.execute();
                    }
                    // le convertisseur a déjà écrit le n° de ligne Excel dans la colonne _row_num
                    st.execute("CREATE OR REPLACE VIEW " + VIEW + " AS SELECT CAST(\"_row_num\" AS BIGINT) AS \"_row_num\","
                            + " * EXCLUDE (\"_row_num\") FROM " + TABLE);
                }
                case PARQUET -> {
                    try (var ps = c.prepareStatement(
                            "CREATE OR REPLACE TABLE " + TABLE + " AS SELECT COLUMNS(*)::VARCHAR FROM read_parquet(?)")) {
                        ps.setString(1, file.toString());
                        ps.execute();
                    }
                    st.execute("CREATE OR REPLACE VIEW " + VIEW + " AS SELECT CAST(rowid AS BIGINT) + 1 AS \"_row_num\", * FROM " + TABLE);
                }
                default -> throw new ImportRejectedException(
                        "Le format " + settings.format() + " n'est pas encore pris en charge par cette version.");
            }
            return describe(c);
        } finally {
            Files.deleteIfExists(work);
        }
    }

    private static LoadResult describe(Connection c) throws SQLException {
        var columns = new ArrayList<String>();
        try (var st = c.createStatement();
                var rs = st.executeQuery("SELECT column_name FROM (DESCRIBE " + VIEW + ") WHERE column_name <> '_row_num'")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        long rows;
        try (var st = c.createStatement();
                var rs = st.executeQuery("SELECT count(*) FROM " + TABLE)) {
            rs.next();
            rows = rs.getLong(1);
        }
        if (rows == 0) {
            throw new ImportRejectedException("Le fichier ne contient aucune ligne de données sous l'en-tête.");
        }
        return new LoadResult(rows, columns);
    }

    /** Recopie un fichier texte en UTF-8, par blocs, si son encodage est différent (Windows-1252 le plus souvent). */
    private static Path toUtf8(Path file, String encoding, Path target) throws IOException {
        Charset source = Charset.forName(encoding);
        if (source.equals(StandardCharsets.UTF_8)) {
            return file;
        }
        try (var in = Files.newBufferedReader(file, source);
                var out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            in.transferTo(out);
        }
        return target;
    }
}
```

**Explication.**

- **`read_csv(…)`** : DuckDB lit le CSV lui-même, en flux et sur plusieurs cœurs. C'est ce qui rend l'objectif de 15 minutes pour 2 Go réaliste (PERF-01). `all_varchar = true` garde chaque valeur en texte, exactement comme dans le fichier : « 007 » reste « 007 », au lieu de devenir le nombre 7. `skip` saute les lignes au-dessus de l'en-tête (un titre d'export, par exemple). `null_padding` complète par des valeurs vides une ligne qui a moins de colonnes que l'en-tête, au lieu de la rejeter.
- **La vue et `rowid`** : `rowid` est la position de chaque ligne dans la table, dans l'ordre du fichier. La vue calcule le numéro de ligne d'origine (`rowid` + ligne d'en-tête + 1) sans recopier les données : une vue ne coûte rien en disque.
- **Excel** passe par le CSV de 17.4, dont la colonne `_row_num` contient déjà le vrai numéro de ligne ; `* EXCLUDE ("_row_num")` évite de l'avoir deux fois.
- **Parquet** : `COLUMNS(*)::VARCHAR` convertit toutes les colonnes en texte, pour que les règles voient les données de la même façon quel que soit le format.
- **Windows-1252** : beaucoup d'exports Excel « CSV » de la CNAM seront dans cet encodage (accents illisibles sinon). Le fichier est recopié en UTF-8 par blocs avant lecture ; le fichier temporaire est supprimé dans le `finally`, même en cas d'erreur.
- **Les paramètres `?` dans `read_csv`** : le chemin et le séparateur passent en paramètres, jamais collés dans le SQL.

### 17.6 — La suite du lot avec Claude Code

Le service d'import (enregistrement dans `source_file`, appel de `FileInspector` puis `DuckDbLoader`), l'écran et l'envoi par morceaux s'écrivent sur ce socle :

```text
Lot import (CDC §5.1). Le chargement existe : FileInspector, ExcelToCsvConverter, DuckDbLoader (étape 17 du guide).
Propose un plan, attends ma validation, puis :
1. ImportService à la racine du module importing : reçoit un Path temporaire + nom d'origine + ImportSettings + l'utilisateur,
   appelle FileInspector, insère source_file (nom, taille, sha256, format, import_settings en JSON, uploaded_by),
   déplace le fichier vers dq.storage.path/uploads/<id>.<ext>, appelle DuckDbLoader, met à jour row_count et column_count.
   Publie un événement Spring FileImported (pour l'audit).
2. Envoi par morceaux de 10 Mo avec reprise (IMP-01, PERF-02) : POST /api/uploads (crée l'envoi, renvoie un id),
   PUT /api/uploads/{id}/chunks/{n} (un morceau), GET /api/uploads/{id} (morceaux reçus, pour reprendre),
   POST /api/uploads/{id}/complete (assemble et lance ImportService). Côté page : un petit script dans static/js qui
   découpe le fichier avec Blob.slice et renvoie les morceaux manquants après une coupure.
3. Écran Thymeleaf « Importer un fichier » : glisser-déposer, choix feuille / ligne d'en-tête / séparateur / encodage,
   aperçu des 50 premières lignes (IMP-04) lu dans la vue ds. Textes dans messages.properties.
4. Détection automatique (IMP-07) : séparateur le plus fréquent sur les 20 premières lignes, encodage UTF-8 sinon windows-1252.
5. Formats restants, chacun converti vers le même CSV que ExcelToCsvConverter (colonne _row_num en tête) :
   XLS et ODS (POI / ODF Toolkit, 100 Mo max), largeur fixe (gabarit position + longueur), XML (StAX, élément répété
   = une ligne), JSON (read_json de DuckDB, objets imbriqués aplatis en adresse.ville), ZIP contenant un seul fichier (IMP-09).
Autorisation : @PreAuthorize("hasAnyRole('ADMIN','ANALYST')") sur tout l'import ; test qu'un READER reçoit 403.
Tests : un fichier d'exemple par format dans src/test/resources/imports/, avec le nombre de lignes et les n° de ligne attendus.
```

**Vérification de fin de lot.** Chaque format du CDC §5.1 bis s'importe sur un fichier d'exemple (critère de recette) ; un fichier `.exe` renommé en `.xlsx` est refusé avec un message clair ; dans l'aperçu, le numéro de ligne affiché correspond à la ligne du fichier d'origine.

## Étape 18 — Profilage des colonnes

Juste après l'import, l'outil décrit chaque colonne : remplissage, valeurs distinctes, longueurs, type probable, valeurs et motifs les plus fréquents (PRF-01 à PRF-03), puis propose des règles (PRF-04). L'analyste voit en un coup d'œil ce que contient le fichier avant de choisir ses contrôles. Nouveau module : `profiling`. Code compilé, SQL vérifié sur DuckDB.

`profiling/ColumnProfile.java`

```java
package ci.cnam.dqanalyzer.profiling;

import java.util.List;

/**
 * Profil d'une colonne (PRF-01 à PRF-03).
 *
 * @param detectedType type majoritaire parmi les cellules remplies : ENTIER, DECIMAL, DATE ou TEXTE
 * @param topValues 10 valeurs les plus fréquentes, avec leur nombre d'apparitions
 * @param patterns motifs dominants : chaque chiffre devient 9, chaque majuscule A, chaque minuscule a
 */
public record ColumnProfile(
        String column,
        long rowCount,
        long filled,
        long distinctValues,
        Integer minLength,
        Integer maxLength,
        String minValue,
        String maxValue,
        String detectedType,
        List<ValueCount> topValues,
        List<ValueCount> patterns) {

    public record ValueCount(String value, long count) {}

    public long empty() {
        return rowCount - filled;
    }

    public double fillRate() {
        return rowCount == 0 ? 0 : filled * 100.0 / rowCount;
    }
}
```

`profiling/domain/ColumnProfiler.java`

```java
package ci.cnam.dqanalyzer.profiling.domain;

import ci.cnam.dqanalyzer.profiling.ColumnProfile;
import ci.cnam.dqanalyzer.profiling.ColumnProfile.ValueCount;
import ci.cnam.dqanalyzer.rules.Dataset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Calcule le profil de chaque colonne d'un fichier importé, en 3 requêtes DuckDB par colonne.
 * Un type est retenu quand au moins 95 % des cellules remplies s'y convertissent : un fichier réel contient
 * toujours quelques valeurs mal saisies, et ce sont justement elles que les règles doivent trouver.
 */
@Component
public class ColumnProfiler {

    private static final double TYPE_SHARE = 0.95;

    public List<ColumnProfile> profile(Dataset data) throws SQLException {
        var result = new ArrayList<ColumnProfile>();
        for (String column : data.columns()) {
            result.add(profile(data, column));
        }
        return result;
    }

    private ColumnProfile profile(Dataset data, String column) throws SQLException {
        String c = data.quotedColumn(column);
        String v = "trim(CAST(" + c + " AS VARCHAR))";
        String filled = c + " IS NOT NULL AND " + v + " <> ''";
        String f = " FILTER (WHERE " + filled + ")";
        String stats = "SELECT count(*), count(*)" + f + ", count(DISTINCT " + v + ")" + f + ","
                + " min(length(" + v + "))" + f + ", max(length(" + v + "))" + f + ","
                + " min(" + v + ")" + f + ", max(" + v + ")" + f + ","
                + " count(TRY_CAST(" + v + " AS BIGINT)), count(TRY_CAST(replace(" + v + ", ',', '.') AS DOUBLE)),"
                + " count(try_strptime(" + v + ", '%d/%m/%Y')) + count(try_strptime(" + v + ", '%Y-%m-%d'))"
                + " FROM " + data.quotedTable();
        try (var st = data.connection().createStatement();
                var rs = st.executeQuery(stats)) {
            rs.next();
            long rows = rs.getLong(1);
            long filledCount = rs.getLong(2);
            String type = detectType(filledCount, rs.getLong(8), rs.getLong(9), rs.getLong(10));
            return new ColumnProfile(column, rows, filledCount, rs.getLong(3),
                    nullableInt(rs, 4), nullableInt(rs, 5),
                    rs.getString(6), rs.getString(7), type,
                    counts(data, v, filled, 10),
                    counts(data, "regexp_replace(regexp_replace(regexp_replace(" + v + ", '[0-9]', '9', 'g'),"
                            + " '[A-Z]', 'A', 'g'), '[a-z]', 'a', 'g')", filled, 5));
        }
    }

    private static Integer nullableInt(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? null : (int) value;
    }

    private static String detectType(long filled, long integers, long decimals, long dates) {
        if (filled == 0) {
            return "VIDE";
        }
        if (integers >= TYPE_SHARE * filled) {
            return "ENTIER";
        }
        if (decimals >= TYPE_SHARE * filled) {
            return "DECIMAL";
        }
        if (dates >= TYPE_SHARE * filled) {
            return "DATE";
        }
        return "TEXTE";
    }

    private static List<ValueCount> counts(Dataset data, String expression, String filled, int limit)
            throws SQLException {
        String sql = "SELECT " + expression + " AS val, count(*) AS n FROM " + data.quotedTable()
                + " WHERE " + filled + " GROUP BY 1 ORDER BY n DESC, val LIMIT " + limit;
        var result = new ArrayList<ValueCount>();
        try (var st = data.connection().createStatement();
                var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new ValueCount(rs.getString(1), rs.getLong(2)));
            }
        }
        return result;
    }
}
```

`profiling/domain/RuleSuggester.java`

```java
package ci.cnam.dqanalyzer.profiling.domain;

import ci.cnam.dqanalyzer.profiling.ColumnProfile;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Propose des règles à partir du profil (PRF-04). Ce ne sont que des suggestions : l'analyste coche ce qu'il garde. */
@Component
public class RuleSuggester {

    public record Suggestion(String column, String ruleType, String reason) {}

    public List<Suggestion> suggest(List<ColumnProfile> profiles) {
        var result = new ArrayList<Suggestion>();
        for (var p : profiles) {
            if (p.filled() == 0) {
                continue;
            }
            if (p.empty() == 0) {
                result.add(new Suggestion(p.column(), "CMP-01", "Colonne toujours renseignée"));
            }
            if (p.distinctValues() == p.filled() && p.filled() > 1) {
                result.add(new Suggestion(p.column(), "UNI-01", "Toutes les valeurs sont différentes"));
            }
            switch (p.detectedType()) {
                case "ENTIER" -> result.add(new Suggestion(p.column(), "VAL-01", "Presque toutes les valeurs sont des entiers"));
                case "DECIMAL" -> result.add(new Suggestion(p.column(), "VAL-01", "Presque toutes les valeurs sont des nombres"));
                case "DATE" -> result.add(new Suggestion(p.column(), "VAL-06", "Presque toutes les valeurs sont des dates"));
                default -> {
                    if (p.distinctValues() > 0 && p.distinctValues() <= 10 && p.filled() >= 20) {
                        result.add(new Suggestion(p.column(), "VAL-05", "Peu de valeurs différentes : liste fermée probable"));
                    }
                }
            }
            if (p.minLength() != null && p.minLength().equals(p.maxLength()) && p.minLength() > 1) {
                result.add(new Suggestion(p.column(), "VAL-03", "Toutes les valeurs font " + p.minLength() + " caractères"));
            }
        }
        return result;
    }
}
```

**Explication.**

- **Une requête par colonne pour les statistiques** : `count(*) FILTER (WHERE …)` calcule plusieurs comptes en une seule lecture de la colonne. Comme DuckDB stocke les données par colonne, il ne lit que la colonne concernée : profiler 30 colonnes de 100 000 lignes prend quelques secondes.
- **`FILTER (WHERE rempli)`** sur le nombre de valeurs distinctes, les longueurs et le minimum : une cellule vide n'est pas une valeur ; sans ce filtre, la longueur minimale vaudrait toujours 0.
- **Le seuil de 95 %** pour le type : si 99 % des âges sont des nombres et 1 % contient « abc », la colonne est « ENTIER », et c'est la règle VAL-01 qui signalera les 1 %. Avec un seuil de 100 %, une seule erreur ferait passer la colonne en « TEXTE » et aucune règle de type ne serait proposée.
- **Les motifs** (PRF-03) : « 0707123456 » devient « 9999999999 », « AB-123 » devient « AA-999 ». Si 98 % des valeurs suivent un motif et 2 % un autre, ces 2 % sont presque toujours des erreurs de saisie : c'est le meilleur point de départ pour une règle VAL-02.
- **`RuleSuggester`** : des suggestions simples et explicables, que l'analyste valide en cochant. Elles accélèrent la création d'un modèle sans jamais créer de règle à son insu.

**Demande à Claude Code pour l'écran :**

```text
Écran « Profil du fichier » (CDC §5.2), affiché juste après l'import. Utilise ColumnProfiler et RuleSuggester
(étape 18 du guide) sur la base DuckDB du fichier (DuckDbWorkspace.open + Dataset sur la vue ds).
Un tableau Bootstrap : une ligne par colonne avec type détecté, taux de remplissage (barre de progression), nb distinctes,
longueurs min/max ; un clic sur la ligne déplie (HTMX) les 10 valeurs les plus fréquentes et les 5 motifs.
Sous le tableau : les règles suggérées, avec une case à cocher chacune et un bouton « Créer les règles cochées »
qui pré-remplit le formulaire de règle (étape 19). Calcul du profil mis en cache dans source_file.profile (colonne JSONB
ajoutée par la migration V2 de l'étape 19) pour ne pas le recalculer à chaque affichage.
Test : ColumnProfilerTest sur un petit CSV, avec les valeurs attendues pour chaque statistique.
```

**Vérification de fin de lot.** Sur un fichier connu, chaque statistique de l'écran correspond à ce que montre Excel (filtres, NB.SI) ; une colonne de numéros toute différente déclenche bien la suggestion UNI-01.

## Étape 19 — Gestion des règles et des modèles

Ce lot donne à l'analyste les écrans pour créer, modifier, tester et ranger ses règles (REG-01 à REG-06), et pour composer des modèles d'analyse (MOD-01 à MOD-03). Le cœur métier (versions, propriétaire métier, association des colonnes) est donné en code ; les écrans se font avec Claude Code. Branche conseillée : `feat/regles-modeles`.

### 19.1 — Migration V2 : propriétaire métier et validation

Ton service fonctionne en gouvernance fédérée : chaque direction métier fixe les règles sur ses données, le service les implémente. Le schéma V1 ne disait pas à qui appartient une règle ; cette migration l'ajoute.

`src/main/resources/db/migration/V2__rule_ownership_and_profile.sql`

```sql
-- V2 : propriétaire métier des règles (gouvernance fédérée), profil des fichiers en cache, numérotation des règles.

-- Chaque règle appartient à une direction métier, qui la valide (modèle de gouvernance fédéré)
ALTER TABLE dq_rule
    ADD COLUMN business_owner  VARCHAR(200),
    ADD COLUMN validated_by    VARCHAR(200),
    ADD COLUMN validated_on    DATE,
    ADD COLUMN change_comment  VARCHAR(500);

-- Numéros de règle lisibles : R-0001, R-0002…
CREATE SEQUENCE dq_rule_code_seq START 1;

-- Profil des colonnes calculé à l'import, gardé pour ne pas le recalculer à chaque affichage
ALTER TABLE source_file ADD COLUMN profile JSONB;
```

**Explication.** C'est la première migration après V1 : on ne touche pas à `V1__init_schema.sql`, on ajoute `V2`. Flyway l'appliquera au prochain démarrage, sur ton poste comme sur le serveur. `business_owner` dit quelle direction porte la règle, `validated_by` / `validated_on` qui l'a validée et quand, `change_comment` pourquoi une version a été créée. Le CDC exclut le workflow de validation en V1 : on trace la validation, sans circuit d'approbation. Une `SEQUENCE` est un compteur géré par PostgreSQL, sûr même si deux analystes créent une règle au même instant.

### 19.2 — Versions des règles : `rules/RuleCatalogService.java`

```java
package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Création et modification des règles, avec versions (REG-01, REG-04). Une modification ne remplace jamais
 * une règle : elle crée une nouvelle ligne (version + 1) et retire le statut « courant » de l'ancienne.
 * Les analyses passées gardent donc la version qu'elles ont utilisée (TRA-02).
 */
@Service
public class RuleCatalogService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RuleEvaluatorRegistry registry;

    public RuleCatalogService(JdbcTemplate jdbc, ObjectMapper json, RuleEvaluatorRegistry registry) {
        this.jdbc = jdbc;
        this.json = json;
        this.registry = registry;
    }

    /** Ce que l'analyste saisit dans le formulaire de règle. */
    public record RuleForm(
            String name,
            String description,
            String type,
            List<String> targetColumns,
            Map<String, Object> params,
            Normalization normalization,
            Severity severity,
            double thresholdPercent,
            String message,
            String businessOwner,
            String changeComment) {}

    /** Crée la version 1 d'une nouvelle règle et renvoie son code (R-0001…). */
    @Transactional
    public String create(RuleForm form, long userId) {
        var evaluator = registry.get(form.type());
        String code = jdbc.queryForObject("SELECT 'R-' || lpad(nextval('dq_rule_code_seq')::text, 4, '0')", String.class);
        insertVersion(code, 1, form, evaluator.dimension(), userId);
        return code;
    }

    /** Modifie une règle : nouvelle version, l'ancienne reste en base pour l'historique. */
    @Transactional
    public int update(String ruleCode, RuleForm form, long userId) {
        var evaluator = registry.get(form.type());
        Integer current = jdbc.queryForObject(
                "SELECT version FROM dq_rule WHERE rule_code = ? AND is_current FOR UPDATE", Integer.class, ruleCode);
        jdbc.update("UPDATE dq_rule SET is_current = FALSE WHERE rule_code = ? AND is_current", ruleCode);
        insertVersion(ruleCode, current + 1, form, evaluator.dimension(), userId);
        return current + 1;
    }

    /** Active ou désactive une règle sans créer de version : elle n'est simplement plus exécutée. */
    @Transactional
    public void setActive(String ruleCode, boolean active) {
        jdbc.update("UPDATE dq_rule SET active = ? WHERE rule_code = ? AND is_current", active, ruleCode);
    }

    /** Enregistre la validation d'une version par la direction métier propriétaire. */
    @Transactional
    public void validate(String ruleCode, String validatedBy) {
        jdbc.update("UPDATE dq_rule SET validated_by = ?, validated_on = current_date WHERE rule_code = ? AND is_current",
                validatedBy, ruleCode);
    }

    private void insertVersion(String code, int version, RuleForm form, Dimension dimension, long userId) {
        var norm = form.normalization() == null ? Normalization.NONE : form.normalization();
        jdbc.update("""
                INSERT INTO dq_rule (rule_code, version, is_current, name, description, dimension, rule_type,
                                     target_columns, params, normalization, severity, threshold, message,
                                     created_by, business_owner, change_comment)
                VALUES (?, ?, TRUE, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """,
                code, version, form.name(), form.description(), dimension.name(), form.type(),
                json.writeValueAsString(form.targetColumns()),
                json.writeValueAsString(form.params() == null ? Map.of() : form.params()),
                json.writeValueAsString(Map.of(
                        "trim", norm.trim(), "upperCase", norm.upperCase(), "stripAccents", norm.stripAccents())),
                form.severity().name(), form.thresholdPercent(), form.message(), userId,
                form.businessOwner(), form.changeComment());
    }
}
```

**Explication.**

- **`@Transactional`** : les deux opérations d'une modification (retirer le statut courant de l'ancienne version, insérer la nouvelle) réussissent ensemble ou échouent ensemble. Sans transaction, une panne entre les deux laisserait une règle sans version courante.
- **`FOR UPDATE`** : verrouille la ligne pendant la modification. Si deux analystes enregistrent la même règle au même moment, le second attend la fin du premier et crée la version suivante, au lieu de créer deux « version 3 ».
- **La dimension vient de l'évaluateur**, pas du formulaire : un CMP-01 est toujours une règle de complétude, l'analyste ne peut pas se tromper.
- **`?::jsonb`** : les paramètres sont transformés en texte JSON par Jackson, puis convertis en JSONB par PostgreSQL. Une valeur saisie ne peut pas modifier la requête.
- **Désactiver n'est pas modifier** : `setActive` change seulement l'état de la version courante. Une règle désactivée disparaît des analyses suivantes mais reste visible, avec tout son historique.

### 19.3 — Association des colonnes : `templates/ColumnMatcher.java`

```java
package ci.cnam.dqanalyzer.templates;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Association automatique des colonnes attendues par un modèle avec celles d'un fichier (MOD-02) :
 * « Date de naissance », « date_de_naissance » et « DATE DE NAISSANCE » désignent la même colonne.
 * L'analyste corrige ensuite à la main ce qui n'a pas été reconnu.
 */
public final class ColumnMatcher {

    private ColumnMatcher() {}

    /** @return colonne attendue → colonne du fichier, ou null si rien ne correspond */
    public static Map<String, String> match(List<String> expected, List<String> fileColumns) {
        var byKey = new HashMap<String, String>();
        for (String column : fileColumns) {
            byKey.putIfAbsent(key(column), column);
        }
        var result = new LinkedHashMap<String, String>();
        for (String column : expected) {
            result.put(column, byKey.get(key(column)));
        }
        return result;
    }

    /** Minuscules, sans accents, et tout ce qui n'est ni lettre ni chiffre retiré. */
    static String key(String name) {
        String noAccents = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
```

**Explication.** `Normalizer.normalize(…, NFD)` sépare chaque lettre accentuée en lettre + accent (« é » → « e » + « ´ ») ; `\p{M}` supprime les accents. On garde ensuite seulement les lettres et les chiffres : « N° sécu » et « n\_secu » donnent tous les deux « nsecu ». Testé : « Date de naissance » est associée à « DATE\_DE\_NAISSANCE », « Commune » reste sans correspondance quand le fichier n'a qu'une colonne « num ». C'est le cas où l'analyste choisit à la main, et ce choix est mémorisé dans `analysis_template.column_mapping` (MOD-03).

**Important pour l'exécution.** Les règles d'un modèle désignent les colonnes attendues (« Date de naissance »). Avant de lancer l'analyse, le service remplace ces noms par ceux du fichier grâce à l'association. Demande-le explicitement à Claude Code (ci-dessous).

### 19.4 — Textes des formulaires

Ajoute à `messages.properties` les libellés des paramètres déclarés par les règles :

```properties
param.placeholders=Valeurs interdites (séparées par ;)
param.when=Condition (SI)
param.then=Contrôle (ALORS)
param.dataType=Type attendu
param.dateFormat=Format de date (ex. jj/mm/aaaa)
param.dateFormats=Formats de date acceptés (séparés par ;)
param.preset=Motif prédéfini
param.regex=Expression régulière
param.minLength=Longueur minimale
param.maxLength=Longueur maximale
param.min=Valeur minimale
param.max=Valeur maximale
param.allowedValues=Valeurs autorisées (séparées par ;)
param.forbiddenCharacters=Caractères interdits (ou « chiffres », « speciaux »)
param.referenceTable=Table de référence
param.referenceKey=Colonne clé dans la référence
param.referenceValue=Colonne comparée dans la référence
param.referenceColumn=Colonne de la référence
param.algorithm=Algorithme de clé (luhn ou mod97)
param.operator=Comparaison
param.valueType=Comparer comme (nombre, date, texte)
param.expression=Calcul attendu
param.tolerance=Tolérance d'écart
param.maxAge=Âge maximal
param.ageUnit=Unité (jours, mois, ans)
param.similarityAlgorithm=Mesure de ressemblance (jaro-winkler ou levenshtein)
param.similarityThreshold=Seuil de ressemblance
param.blockColumn=Comparer seulement les lignes de même…
rule.type.CMP-01=Champ obligatoire non vide
rule.type.CMP-02=Valeur bouche-trou interdite
rule.type.CMP-03=Obligatoire sous condition
rule.type.CMP-04=Taux de remplissage minimal
rule.type.UNI-01=Valeur unique sur une colonne
rule.type.UNI-02=Clé composée unique
rule.type.UNI-03=Doublons après normalisation
rule.type.UNI-04=Doublons approchés
rule.type.VAL-01=Type de donnée
rule.type.VAL-02=Format (motif)
rule.type.VAL-03=Longueur
rule.type.VAL-04=Plage de valeurs
rule.type.VAL-05=Liste de valeurs autorisées
rule.type.VAL-06=Format de date
rule.type.VAL-07=Caractères interdits
rule.type.EXA-01=Concordance avec une référence
rule.type.EXA-02=Clé de contrôle
rule.type.COH-01=Comparaison entre deux colonnes
rule.type.COH-02=Règle SI… ALORS
rule.type.COH-03=Valeur calculée
rule.type.ACT-01=Âge maximal de la donnée
rule.type.ACT-02=Date non future
rule.type.INT-01=Existence dans une référence
```

**Explication.** Chaque `Param` des règles porte une clé (`param.min`) ; le formulaire généré lit son libellé ici. Ajouter une règle avec un paramètre nouveau = une ligne ici, aucun HTML à écrire.

### 19.5 — Les écrans avec Claude Code

```text
Lot gestion des règles et des modèles (CDC §5.3 et §5.4). Le métier existe : RuleCatalogService, ColumnMatcher,
ParamSchema de chaque évaluateur, libellés param.* et rule.type.* dans messages.properties (étape 19 du guide).
Propose un plan, attends ma validation, puis :
1. Liste des règles (REG-06) : tableau filtrable par dimension, modèle, propriétaire métier, auteur ; version, statut
   actif, date de validation ; boutons Modifier, Dupliquer, Activer/Désactiver, Historique des versions.
2. Formulaire de règle (REG-01, REG-02) : choix du type, puis champs générés à partir de paramSchema() :
   ColumnArity → 1, 2 ou plusieurs listes de colonnes ; TEXT/INTEGER/DECIMAL → champ ; TEXT_LIST → champ « a; b » ;
   REFERENCE_TABLE → liste des tables de référence ; EXPRESSION → éditeur de condition sans texte libre
   (une ligne = colonne + opérateur + valeur + type, boutons « ajouter ET / OU », construit le JSON de Condition).
   Champs communs : nom, description, sévérité, seuil %, message, normalisation (3 cases), propriétaire métier,
   commentaire de modification. Validation : appeler l'évaluateur sur un Dataset vide pour remonter les erreurs
   de paramètres (RuleEvaluationException) avant d'enregistrer.
3. Tester une règle sur l'aperçu (REG-03) : bouton qui exécute la règle sur un fichier importé choisi, sans rien
   enregistrer (AnomalySink en mémoire limité à 50 anomalies), affiche lignes contrôlées / conformes / anomalies.
4. Export / import JSON des règles (REG-05).
5. Modèles (MOD-01 à MOD-03) : nom, description, colonnes attendues, règles cochées et ordonnées, poids par
   dimension ; à l'association d'un fichier, ColumnMatcher propose, l'analyste corrige, la correspondance est
   enregistrée dans analysis_template.column_mapping. Avant exécution, remplacer les colonnes des règles par celles
   du fichier selon cette correspondance.
Droits : création / modification réservées à ADMIN et ANALYST (@PreAuthorize) ; READER en lecture seule ;
test MockMvc qui prouve qu'un READER reçoit 403 sur chaque POST et PUT.
```

**Vérification de fin de lot.** Modifier une règle crée la version 2 et laisse la version 1 visible dans l'historique ; un Lecteur ne peut ni créer ni modifier une règle (critère de recette) ; une analyse complète se prépare sans écrire de code.

## Étape 20 — Tables de référence

Les règles EXA-01 et INT-01 comparent le fichier à un référentiel (communes, nomenclature d'actes, base des assurés). Les références sont gardées dans PostgreSQL, versionnées et datées (REF-01, REF-02), parce qu'elles doivent survivre à la purge des fichiers à 90 jours et être sauvegardées avec la base. Au moment d'une analyse, elles sont copiées dans la base DuckDB du fichier, où les règles les interrogent par jointure (REF-03). Nouveau module : `reference`. Code compilé.

`reference/ReferenceLoader.java`

```java
package ci.cnam.dqanalyzer.reference;

import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Copie les tables de référence utilisées par une analyse depuis PostgreSQL (où elles sont versionnées et
 * sauvegardées) vers la base DuckDB de l'analyse, dans des tables « ref_<id> ». Les règles EXA-01 et INT-01
 * les interrogent ensuite par une simple jointure, sans aller-retour avec PostgreSQL.
 */
@Component
public class ReferenceLoader {

    private static final int BATCH = 1_000;
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReferenceLoader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public ReferenceProvider loadInto(Connection duck, Collection<Long> referenceTableIds) throws SQLException {
        var tables = new HashMap<Long, String>();
        for (long id : referenceTableIds) {
            List<String> columns = json.readValue(
                    jdbc.queryForObject("SELECT columns::text FROM reference_table WHERE id = ?", String.class, id), STRINGS);
            String table = "ref_" + id;
            var ddl = new StringBuilder("CREATE OR REPLACE TABLE " + DuckDbIdentifiers.quote(table) + " (");
            for (int i = 0; i < columns.size(); i++) {
                ddl.append(i == 0 ? "" : ", ").append(DuckDbIdentifiers.quote(columns.get(i))).append(" VARCHAR");
            }
            try (var st = duck.createStatement()) {
                st.execute(ddl.append(')').toString());
            }
            String insert = "INSERT INTO " + DuckDbIdentifiers.quote(table) + " VALUES ("
                    + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
            try (PreparedStatement ps = duck.prepareStatement(insert)) {
                int[] pending = {0};
                jdbc.query("SELECT row_values::text FROM reference_value WHERE reference_table_id = ? ORDER BY id", rs -> {
                    try {
                        Map<String, Object> row = json.readValue(rs.getString(1), OBJECT);
                        for (int i = 0; i < columns.size(); i++) {
                            Object value = row.get(columns.get(i));
                            ps.setString(i + 1, value == null ? null : value.toString());
                        }
                        ps.addBatch();
                        if (++pending[0] % BATCH == 0) {
                            ps.executeBatch();
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException("Copie de la table de référence " + id + " impossible", e);
                    }
                }, id);
                ps.executeBatch();
            }
            tables.put(id, table);
        }
        return id -> {
            String table = tables.get(id);
            if (table == null) {
                throw new RuleEvaluationException("La table de référence n° " + id + " n'a pas été chargée pour cette analyse");
            }
            return table;
        };
    }
}
```

**Explication.**

- **Deux bases, deux rôles** : PostgreSQL garde la référence de façon durable (versions, sauvegardes, traçabilité) ; DuckDB l'utilise le temps d'une analyse, à côté du fichier, pour que la jointure soit rapide. On ne fait jamais une requête PostgreSQL par ligne du fichier, ce qui serait des milliers de fois plus lent.
- **`reference_value.row_values` en JSONB** : une ligne de référence est un objet `{"code": "001", "nom": "PLATEAU"}`. Les colonnes diffèrent d'une référence à l'autre ; une colonne JSONB évite de créer une table PostgreSQL par référentiel.
- **`jdbc.query(…, rs -> {…})`** (un `RowCallbackHandler`) : les lignes sont lues une par une et insérées par paquets de 1 000 dans DuckDB, jamais toutes en mémoire. `@Transactional(readOnly = true)` permet au pilote PostgreSQL de lire le résultat par blocs au lieu de tout télécharger d'un coup.
- **Le `ReferenceProvider` renvoyé** traduit le numéro de référence d'une règle (`referenceTableId`) en nom de table DuckDB. Une règle qui demande une référence non chargée reçoit un message clair au lieu d'une erreur SQL.
- **Traçabilité (TRA-02)** : le service d'exécution (étape 21) enregistre dans `run_reference` les versions exactes utilisées. Une analyse rouverte dans un an saura sur quelle version du référentiel elle a été faite.

**Demande à Claude Code pour le chargement et l'écran :**

```text
Lot tables de référence (CDC §5.5). ReferenceLoader existe (étape 20 du guide).
1. ReferenceTableService à la racine du module reference : charge un fichier Excel ou CSV avec le même circuit que
   l'import (FileInspector + DuckDbLoader dans une base DuckDB temporaire), puis copie ses lignes dans PostgreSQL :
   une ligne reference_table (nom, version = max(version) + 1 pour ce nom, columns en JSON, row_count, loaded_by)
   et une ligne reference_value par ligne du fichier (row_values JSONB), par lots de 1 000 (JdbcTemplate.batchUpdate).
   Ne jamais remplacer une version existante (REF-02).
2. Écran « Tables de référence » : liste (nom, version courante, date, nb de lignes, auteur), historique des versions,
   aperçu des 50 premières lignes, bouton « Nouvelle version ». Les règles EXA-01 et INT-01 proposent la liste des
   tables dans leur formulaire (ParamType.REFERENCE_TABLE).
3. Droits : ADMIN et ANALYST chargent, READER consulte.
Tests : ReferenceTableServiceIT (Testcontainers) qui charge deux versions et vérifie que la v1 reste intacte ;
un test qui charge une référence dans DuckDB avec ReferenceLoader et exécute INT-01 dessus.
```

**Vérification de fin de lot.** Charger deux fois le référentiel des communes crée les versions 1 et 2 ; une règle INT-01 qui pointe la version 2 détecte un code supprimé dans cette version.

## Étape 21 — Exécution des analyses

Ce lot relie tout : un clic lance l'analyse en tâche de fond (EXE-01, EXE-02), chaque règle du modèle s'exécute sur la base DuckDB du fichier, les résultats et anomalies sont enregistrés dans PostgreSQL, puis les scores sont calculés avec `Scoring`. Le code est complet et compilé ; les tests d'intégration sont à faire avec Claude Code (ci-dessous). Branche conseillée : `feat/execution`.

### 21.1 — Lire les règles d'un modèle : `rules/RuleDefinitionReader.java`

```java
package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Lit dans PostgreSQL les règles d'un modèle d'analyse, dans leur version courante et active, et les transforme
 * en {@link RuleDefinition}. L'identifiant renvoyé est celui de la ligne de version : il sera enregistré dans
 * rule_result, ce qui fige la version exacte utilisée par l'analyse (TRA-02).
 */
@Component
public class RuleDefinitionReader {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RuleDefinitionReader(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<RuleDefinition> forTemplate(long templateId) {
        return jdbc.query("""
                SELECT r.id, r.rule_code, r.version, r.name, r.rule_type, r.dimension, r.target_columns::text,
                       r.params::text, r.normalization::text, r.severity, r.threshold, r.message
                FROM template_rule t
                JOIN dq_rule r ON r.rule_code = t.rule_code AND r.is_current AND r.active
                WHERE t.template_id = ?
                ORDER BY t.position, r.rule_code
                """, (rs, i) -> {
            Map<String, Object> norm = json.readValue(rs.getString(9), OBJECT);
            return new RuleDefinition(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getString(4),
                    rs.getString(5),
                    Dimension.valueOf(rs.getString(6)),
                    json.readValue(rs.getString(7), STRINGS),
                    json.readValue(rs.getString(8), OBJECT),
                    new Normalization(
                            Boolean.TRUE.equals(norm.get("trim")),
                            Boolean.TRUE.equals(norm.get("upperCase")),
                            Boolean.TRUE.equals(norm.get("stripAccents"))),
                    Severity.valueOf(rs.getString(10)),
                    rs.getDouble(11),
                    rs.getString(12));
        }, templateId);
    }
}
```

**Explication.** La jointure `r.is_current AND r.active` prend la version en vigueur de chaque règle du modèle, et ignore les règles désactivées. `::text` fait renvoyer les colonnes JSONB sous forme de texte, que Jackson relit en listes et en objets Java. Avec Spring Boot 4, Jackson est en version 3 : son paquet est `tools.jackson`, et non plus `com.fasterxml.jackson`.

### 21.2 — Adapter les colonnes au fichier : `execution/domain/ColumnRemapper.java`

```java
package ci.cnam.dqanalyzer.execution.domain;

import ci.cnam.dqanalyzer.rules.RuleDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remplace, dans une règle, les noms de colonnes du modèle (« Date de naissance ») par ceux du fichier analysé
 * (« DATE_NAISS »), selon l'association enregistrée dans le modèle (MOD-02, MOD-03). Une colonne absente de
 * l'association garde son nom. Les colonnes citées dans les paramètres (conditions, calculs, colonne de blocage)
 * sont remplacées aussi.
 */
public final class ColumnRemapper {

    private static final Set<String> COLUMN_KEYS = Set.of("column", "blockColumn");

    private ColumnRemapper() {}

    public static RuleDefinition remap(RuleDefinition rule, Map<String, String> mapping) {
        if (mapping.isEmpty()) {
            return rule;
        }
        var columns = rule.targetColumns().stream().map(c -> mapping.getOrDefault(c, c)).toList();
        @SuppressWarnings("unchecked")
        var params = (Map<String, Object>) remapValue(rule.params(), mapping);
        return new RuleDefinition(rule.id(), rule.ruleCode(), rule.version(), rule.name(), rule.type(), rule.dimension(),
                columns, params, rule.normalization(), rule.severity(), rule.thresholdPercent(), rule.message());
    }

    private static Object remapValue(Object value, Map<String, String> mapping) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (COLUMN_KEYS.contains(key) && v != null) {
                    result.put(key, mapping.getOrDefault(v.toString(), v.toString()));
                } else {
                    result.put(key, remapValue(v, mapping));
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<Object>(list.size());
            list.forEach(item -> result.add(remapValue(item, mapping)));
            return result;
        }
        return value;
    }
}
```

**Explication.** Un modèle décrit ses règles avec des noms de colonnes stables (« N° principal ») ; chaque fichier reçu peut les nommer autrement. Le remplacement se fait juste avant l'exécution, en mémoire : la règle enregistrée ne change pas. La fonction parcourt les paramètres en profondeur, parce qu'une condition de CMP-03 cite aussi des colonnes (`"column": "Statut"`). Testé : `N° principal` devient `NUM_PRINC` et la condition sur `Statut` devient `STATUT`.

### 21.3 — Enregistrer les anomalies : `execution/infra/JdbcAnomalySink.java`

```java
package ci.cnam.dqanalyzer.execution.infra;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Écrit les anomalies d'une règle dans la table anomaly de PostgreSQL, par paquets de 1 000 lignes.
 * Au-delà de {@code maxStored} anomalies pour une règle, les suivantes sont comptées mais pas enregistrées :
 * le score reste exact, et une règle qui échoue sur tout un fichier de 2 Go ne remplit pas la base.
 */
public class JdbcAnomalySink implements AnomalySink, AutoCloseable {

    private static final int BATCH = 1_000;
    private static final String INSERT = "INSERT INTO anomaly (run_id, rule_id, row_num, column_name, cell_value,"
            + " message, duplicate_group) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final long runId;
    private final long ruleId;
    private final long maxStored;
    private final List<Object[]> buffer = new ArrayList<>(BATCH);
    private long received;

    public JdbcAnomalySink(JdbcTemplate jdbc, long runId, long ruleId, long maxStored) {
        this.jdbc = jdbc;
        this.runId = runId;
        this.ruleId = ruleId;
        this.maxStored = maxStored;
    }

    @Override
    public void accept(Anomaly a) {
        if (++received > maxStored) {
            return;
        }
        buffer.add(new Object[] {
            runId, ruleId, a.rowNum(), cut(a.column(), 255), cut(a.value(), 1_000), cut(a.message(), 500), a.duplicateGroup()
        });
        if (buffer.size() == BATCH) {
            flush();
        }
    }

    /** Nombre d'anomalies reçues, enregistrées ou non. */
    public long received() {
        return received;
    }

    public boolean truncated() {
        return received > maxStored;
    }

    private void flush() {
        if (!buffer.isEmpty()) {
            jdbc.batchUpdate(INSERT, buffer);
            buffer.clear();
        }
    }

    @Override
    public void close() {
        flush();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
```

**Explication.** Insérer les anomalies une par une ferait un aller-retour réseau par anomalie ; `batchUpdate` en envoie 1 000 à la fois. Avec l'option `reWriteBatchedInserts=true` du pilote PostgreSQL (21.5), chaque paquet devient une seule instruction `INSERT … VALUES (…), (…), …`, plusieurs fois plus rapide. `cut` tronque les valeurs trop longues pour les colonnes de la table au lieu de faire échouer l'analyse. Le plafond par règle (1 million par défaut) évite qu'une règle mal paramétrée sur un fichier de 2 Go écrive des dizaines de millions de lignes : le score reste juste, puisqu'il est calculé sur les comptages, pas sur les anomalies enregistrées.

### 21.4 — Lancer et suivre une analyse

`execution/AnalysisService.java`

```java
package ci.cnam.dqanalyzer.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Point d'entrée des analyses pour les autres modules et les écrans (EXE-01) : crée l'exécution, la confie
 * au {@link AnalysisRunner} en tâche de fond (EXE-02) et rend la main tout de suite avec son numéro.
 */
@Service
public class AnalysisService {

    private final JdbcTemplate jdbc;
    private final AnalysisRunner runner;

    public AnalysisService(JdbcTemplate jdbc, AnalysisRunner runner) {
        this.jdbc = jdbc;
        this.runner = runner;
    }

    /**
     * Pas de @Transactional ici, volontairement : l'insertion doit être validée en base avant que l'autre
     * thread ne lise l'exécution, sinon il ne la trouverait pas.
     */
    public long start(long sourceFileId, long templateId, long userId) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO analysis_run (source_file_id, template_id, launched_by, status, progress)
                VALUES (?, ?, ?, 'PENDING', 0) RETURNING id
                """, Long.class, sourceFileId, templateId, userId);
        runner.run(runId);
        return runId;
    }

    public record Progress(String status, int progress, String resultStatus, Double globalScore, String error) {}

    /** Lu toutes les 2 secondes par la barre de progression (HTMX). */
    public Progress progress(long runId) {
        return jdbc.queryForObject(
                "SELECT status, progress, result_status, global_score, error_message FROM analysis_run WHERE id = ?",
                (rs, i) -> new Progress(rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getDouble(4), rs.getString(5)),
                runId);
    }
}
```

`execution/AnalysisRunner.java`

```java
package ci.cnam.dqanalyzer.execution;

import ci.cnam.dqanalyzer.execution.domain.ColumnRemapper;
import ci.cnam.dqanalyzer.execution.domain.Scoring;
import ci.cnam.dqanalyzer.execution.domain.Scoring.ScoredRule;
import ci.cnam.dqanalyzer.execution.infra.JdbcAnomalySink;
import ci.cnam.dqanalyzer.reference.ReferenceLoader;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleDefinitionReader;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluatorRegistry;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbWorkspace;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Exécute une analyse en tâche de fond : charge les règles du modèle et les tables de référence, lance chaque règle
 * sur la base DuckDB du fichier, enregistre résultats et anomalies, calcule les scores, met à jour la progression.
 * Appelé uniquement par {@link AnalysisService} : @Async ne fonctionne que si l'appel vient d'un autre bean.
 */
@Component
public class AnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);
    private static final TypeReference<Map<String, Double>> WEIGHTS = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> MAPPING = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DuckDbWorkspace workspace;
    private final RuleDefinitionReader rules;
    private final RuleEvaluatorRegistry registry;
    private final ReferenceLoader references;
    private final Scoring scoring = new Scoring();
    private final long maxAnomaliesPerRule;

    public AnalysisRunner(JdbcTemplate jdbc, ObjectMapper json, DuckDbWorkspace workspace, RuleDefinitionReader rules,
            RuleEvaluatorRegistry registry, ReferenceLoader references,
            @Value("${dq.analysis.max-anomalies-per-rule:1000000}") long maxAnomaliesPerRule) {
        this.jdbc = jdbc;
        this.json = json;
        this.workspace = workspace;
        this.rules = rules;
        this.registry = registry;
        this.references = references;
        this.maxAnomaliesPerRule = maxAnomaliesPerRule;
    }

    @Async
    public void run(long runId) {
        var run = jdbc.queryForMap("SELECT source_file_id, template_id FROM analysis_run WHERE id = ?", runId);
        long fileId = ((Number) run.get("source_file_id")).longValue();
        long templateId = ((Number) run.get("template_id")).longValue();
        jdbc.update("UPDATE analysis_run SET status = 'RUNNING', progress = 0 WHERE id = ?", runId);
        try (var duck = workspace.open(fileId)) {
            var mapping = columnMapping(templateId);
            var definitions = rules.forTemplate(templateId).stream()
                    .map(rule -> ColumnRemapper.remap(rule, mapping))
                    .toList();
            if (definitions.isEmpty()) {
                throw new RuleEvaluationException("Le modèle ne contient aucune règle active.");
            }
            var referenceIds = definitions.stream()
                    .map(r -> r.params().get("referenceTableId"))
                    .filter(Objects::nonNull)
                    .map(id -> Long.valueOf(id.toString()))
                    .distinct()
                    .toList();
            var refs = references.loadInto(duck, referenceIds);
            for (long refId : referenceIds) {
                jdbc.update("INSERT INTO run_reference (run_id, reference_table_id) VALUES (?, ?)", runId, refId);
            }
            var dataset = new Dataset(duck, DuckDbWorkspace.DATASET_VIEW, workspace.columns(duck));

            var scored = new ArrayList<ScoredRule>();
            var statuses = new ArrayList<RuleStatus>();
            for (int i = 0; i < definitions.size(); i++) {
                RuleDefinition rule = definitions.get(i);
                var evaluator = registry.get(rule.type());
                var sink = new JdbcAnomalySink(jdbc, runId, rule.id(), maxAnomaliesPerRule);
                var result = evaluator.evaluate(dataset, rule, refs, sink);
                sink.close();
                OptionalDouble score = scoring.ruleScore(result);
                RuleStatus status = scoring.ruleStatus(score, rule.thresholdPercent(), rule.severity());
                jdbc.update("""
                        INSERT INTO rule_result (run_id, rule_id, checked_count, conforming_count, score, status, duplicate_groups)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, runId, rule.id(), result.checkedRows(), result.conformingRows(),
                        score.isPresent() ? round(score.getAsDouble()) : null, status.name(), result.duplicateGroups());
                scored.add(new ScoredRule(rule.dimension(), rule.severity(), rule.targetColumns(), score));
                statuses.add(status);
                jdbc.update("UPDATE analysis_run SET progress = ? WHERE id = ?", (i + 1) * 100 / definitions.size(), runId);
            }

            var dimensionScores = scoring.dimensionScores(scored);
            OptionalDouble global = scoring.globalScore(dimensionScores, dimensionWeights(templateId));
            jdbc.update("""
                    UPDATE analysis_run SET status = 'DONE', progress = 100, result_status = ?, global_score = ?,
                           finished_at = now() WHERE id = ?
                    """, scoring.globalStatus(statuses).name(), global.isPresent() ? round(global.getAsDouble()) : null, runId);
        } catch (Exception e) {
            log.warn("Analyse {} en échec", runId, e);
            String message = e instanceof RuleEvaluationException ? e.getMessage()
                    : "Erreur technique pendant l'analyse. Consultez les journaux de l'application.";
            jdbc.update("DELETE FROM anomaly WHERE run_id = ?", runId);
            jdbc.update("DELETE FROM rule_result WHERE run_id = ?", runId);
            jdbc.update("UPDATE analysis_run SET status = 'FAILED', error_message = ?, finished_at = now() WHERE id = ?",
                    message, runId);
        }
    }

    private Map<String, String> columnMapping(long templateId) {
        String raw = jdbc.queryForObject("SELECT column_mapping::text FROM analysis_template WHERE id = ?", String.class, templateId);
        return json.readValue(raw, MAPPING);
    }

    private Map<Dimension, Double> dimensionWeights(long templateId) {
        String raw = jdbc.queryForObject("SELECT dimension_weights::text FROM analysis_template WHERE id = ?", String.class, templateId);
        var weights = new EnumMap<Dimension, Double>(Dimension.class);
        json.readValue(raw, WEIGHTS).forEach((dimension, weight) -> weights.put(Dimension.valueOf(dimension), weight));
        return weights;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
```

`shared/config/AsyncConfig.java`

```java
package ci.cnam.dqanalyzer.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Active @Async : les analyses tournent en tâche de fond, sur des threads virtuels (EXE-02). */
@Configuration
@EnableAsync
class AsyncConfig {}
```

**Explication.**

- **`@Async`** : Spring exécute `run` dans un autre thread ; `start` rend la main aussitôt avec le numéro d'analyse, et l'écran peut afficher la progression. `@Async` ne marche que si la méthode est appelée depuis un **autre** bean : c'est pourquoi le lancement (`AnalysisService`) et l'exécution (`AnalysisRunner`) sont deux classes.
- **`start` sans transaction** : si l'insertion de `analysis_run` faisait partie d'une transaction encore ouverte, le thread de l'analyse pourrait chercher la ligne avant qu'elle soit validée, et ne pas la trouver.
- **La boucle** : pour chaque règle, l'évaluateur du catalogue, puis le score, le statut et le résultat. `rule.id()` est l'identifiant de la **version** de la règle : `rule_result` garde exactement la version utilisée (TRA-02). La progression passe de 0 à 100 % règle par règle.
- **Les scores** viennent de `Scoring`, avec les poids de dimension du modèle : le calcul est le même partout, comme le prévoyait la partie 1.
- **En cas d'erreur**, l'analyse passe en « FAILED » avec un message lisible si c'est un problème de règle (« La colonne « telephone » n'existe pas dans le fichier »), un message générique sinon. Les résultats partiels sont supprimés : on ne garde pas un rapport à moitié faux. Le détail technique va dans les journaux (`log.warn`), jamais à l'écran.

### 21.5 — Configuration

Dans `application.yml`, ajoute sous `spring:` puis sous `dq:` :

```yaml
spring:
  task:
    execution:
      simple:
        concurrency-limit: 2          # au plus 2 analyses en même temps (disque : 3 × 2 Go chacune)

dq:
  analysis:
    max-anomalies-per-rule: 1000000
```

Et ajoute `?reWriteBatchedInserts=true` à la fin de l'adresse de la base : dans `application-dev.yml` (`jdbc:postgresql://localhost:5432/dqanalyzer?reWriteBatchedInserts=true`) et dans les `compose.yaml` / `compose.prod.yaml` (`SPRING_DATASOURCE_URL`, `DQ_DB_URL`).

**Explication.** Avec les threads virtuels, Spring lance chaque tâche `@Async` dans un nouveau thread léger, sans limite par défaut ; `concurrency-limit` borne le nombre d'analyses simultanées, donc l'espace disque et la mémoire nécessaires (PERF-03). Une analyse demandée au-delà attend son tour. Le chiffre 2 est à ajuster selon le serveur.

### 21.6 — La suite avec Claude Code

```text
Lot exécution (CDC §5.6 EXE). AnalysisService, AnalysisRunner, JdbcAnomalySink, ColumnRemapper existent (étape 21).
1. Écran « Lancer une analyse » : choisir un fichier importé et un modèle, vérifier l'association des colonnes
   (étape 19), bouton Lancer → AnalysisService.start. Puis une page de suivi avec une barre de progression
   rafraîchie par HTMX toutes les 2 s (hx-get + hx-trigger="every 2s") qui s'arrête à DONE ou FAILED
   et redirige vers le rapport.
2. Enregistrer dans rule_result si les anomalies ont été tronquées (JdbcAnomalySink.truncated) : migration V3
   avec une colonne anomalies_truncated BOOLEAN, et l'afficher dans le rapport.
3. Publier un événement Spring AnalysisCompleted (runId, statut) pour le journal d'audit.
4. Test d'intégration AnalysisRunnerIT (Testcontainers) : insère un utilisateur, un fichier importé depuis
   datasets/catalogue_regles.csv, un modèle avec 3 règles (CMP-01 bloquante, VAL-04, UNI-01), lance l'analyse,
   attend DONE, et vérifie rule_result, le nombre d'anomalies, le score global et le statut KO.
   Les scores attendus sont calculés à la main en commentaire (critère de recette « scores = calcul manuel »).
```

**Vérification de fin de lot.** Une analyse sur un fichier déjà associé à un modèle se lance et se termine sans écrire de code, en moins de 5 minutes (critère de recette) ; la barre de progression avance ; un modèle qui cite une colonne absente donne une analyse « FAILED » avec un message clair.

## Étape 22 — Rapport, exports Excel et PDF

Le rapport montre le résultat d'une analyse : score global et statut, scores par dimension, par règle et par colonne (RAP-01, RAP-02), puis les anomalies ligne par ligne, filtrables (RAP-03), avec les doublons regroupés (RAP-04). Il s'exporte en Excel (RAP-05) et en PDF (RAP-06). La lecture des résultats est donnée en code ; les écrans et les fichiers d'export se font avec Claude Code. Branche conseillée : `feat/rapport`.

### 22.1 — Lire un rapport : `execution/RunReportService.java`

```java
package ci.cnam.dqanalyzer.execution;

import ci.cnam.dqanalyzer.execution.domain.Scoring;
import ci.cnam.dqanalyzer.execution.domain.Scoring.ScoredRule;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Lit les résultats d'une analyse terminée pour le rapport (RAP-01 à RAP-04) et l'historique (HIS-02).
 * Les scores par dimension et par colonne sont recalculés avec {@link Scoring} à partir des résultats par règle :
 * le calcul reste à un seul endroit, et une analyse ancienne se relit avec les versions de règles de l'époque.
 */
@Service
public class RunReportService {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Scoring scoring = new Scoring();

    public RunReportService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record RuleLine(String ruleCode, int version, String name, String type, Dimension dimension,
            Severity severity, List<String> columns, long checked, long conforming, Double score,
            double threshold, RuleStatus status, Long duplicateGroups) {

        public long anomalies() {
            return checked - conforming;
        }
    }

    public record Report(long runId, String fileName, long rowCount, Integer columnCount, String templateName,
            String launchedBy, Instant startedAt, Instant finishedAt, String status, String resultStatus,
            Double globalScore, Map<Dimension, Double> dimensionScores, Map<String, Double> columnScores,
            Map<Severity, Long> anomaliesBySeverity, List<RuleLine> rules) {}

    public Report report(long runId) {
        var rules = jdbc.query("""
                SELECT r.rule_code, r.version, r.name, r.rule_type, r.dimension, r.severity, r.target_columns::text,
                       rr.checked_count, rr.conforming_count, rr.score, r.threshold, rr.status, rr.duplicate_groups
                FROM rule_result rr JOIN dq_rule r ON r.id = rr.rule_id
                WHERE rr.run_id = ?
                ORDER BY r.dimension, r.rule_code
                """, (rs, i) -> new RuleLine(
                        rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        Dimension.valueOf(rs.getString(5)), Severity.valueOf(rs.getString(6)),
                        json.readValue(rs.getString(7), STRINGS), rs.getLong(8), rs.getLong(9),
                        rs.getObject(10) == null ? null : rs.getDouble(10), rs.getDouble(11),
                        RuleStatus.valueOf(rs.getString(12)), rs.getObject(13) == null ? null : rs.getLong(13)),
                runId);

        var scored = new ArrayList<ScoredRule>();
        var bySeverity = new EnumMap<Severity, Long>(Severity.class);
        var columns = new LinkedHashSet<String>();
        for (RuleLine rule : rules) {
            scored.add(new ScoredRule(rule.dimension(), rule.severity(), rule.columns(),
                    rule.score() == null ? OptionalDouble.empty() : OptionalDouble.of(rule.score())));
            bySeverity.merge(rule.severity(), rule.anomalies(), Long::sum);
            columns.addAll(rule.columns());
        }
        var columnScores = new LinkedHashMap<String, Double>();
        for (String column : columns) {
            scoring.columnScore(column, scored).ifPresent(score -> columnScores.put(column, score));
        }

        return jdbc.queryForObject("""
                SELECT f.original_name, f.row_count, f.column_count, t.name, u.full_name, a.started_at, a.finished_at,
                       a.status, a.result_status, a.global_score
                FROM analysis_run a
                JOIN source_file f ON f.id = a.source_file_id
                LEFT JOIN analysis_template t ON t.id = a.template_id
                JOIN app_user u ON u.id = a.launched_by
                WHERE a.id = ?
                """, (rs, i) -> new Report(runId, rs.getString(1), rs.getLong(2),
                        rs.getObject(3) == null ? null : rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        rs.getString(8), rs.getString(9), rs.getObject(10) == null ? null : rs.getDouble(10),
                        scoring.dimensionScores(scored), columnScores, bySeverity, rules),
                runId);
    }

    public record AnomalyRow(long rowNum, String column, String value, String ruleCode, String ruleName,
            String severity, String message, Long duplicateGroup) {}

    /** Anomalies d'une analyse, filtrées et paginées (RAP-03). Un filtre null est ignoré. */
    public List<AnomalyRow> anomalies(long runId, String ruleCode, String column, int page, int size) {
        return jdbc.query("""
                SELECT a.row_num, a.column_name, a.cell_value, r.rule_code, r.name, r.severity, a.message, a.duplicate_group
                FROM anomaly a JOIN dq_rule r ON r.id = a.rule_id
                WHERE a.run_id = ?
                  AND (CAST(? AS VARCHAR) IS NULL OR r.rule_code = ?)
                  AND (CAST(? AS VARCHAR) IS NULL OR a.column_name = ?)
                ORDER BY r.rule_code, coalesce(a.duplicate_group, 0), a.row_num
                LIMIT ? OFFSET ?
                """, (rs, i) -> new AnomalyRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getObject(8) == null ? null : rs.getLong(8)),
                runId, ruleCode, ruleCode, column, column, size, (long) page * size);
    }
}
```

**Explication.**

- **Placé dans le module `execution`** : il a besoin de `Scoring`, interne à ce module. Le futur module `reporting` (écrans, exports) appellera ce service public, sans toucher aux classes internes ; `ModularityTest` reste vert.
- **Les scores de dimension et de colonne ne sont pas stockés** : ils se recalculent à partir des résultats par règle. Une seule vérité, et aucun risque qu'un score enregistré et un score affiché divergent.
- **La jointure sur `rr.rule_id`** lit la version exacte de chaque règle utilisée par l'analyse (nom, seuil, sévérité de l'époque) : rouvrir une analyse d'il y a six mois affiche le rapport tel qu'il était (critère de recette).
- **`anomaliesBySeverity`** alimente le bandeau « répartition des anomalies par sévérité » du CDC §7.
- **Anomalies paginées** : `LIMIT … OFFSET …` ne renvoie qu'une page (par exemple 50 lignes). `CAST(? AS VARCHAR) IS NULL OR …` rend chaque filtre facultatif sans fabriquer de SQL à la main. Le tri par groupe de doublons puis par ligne affiche les doublons ensemble (RAP-04). L'index `idx_anomaly_run_rule` de la V1 rend cette requête rapide même avec des millions d'anomalies.

### 22.2 — L'export Excel, principe

L'export Excel du CDC (RAP-05) a trois onglets : Synthèse (scores), Anomalies (liste), et le fichier d'origine avec une colonne « Anomalies » et les cellules en faute surlignées. Le troisième onglet demande de croiser les données (dans DuckDB) et les anomalies (dans PostgreSQL). La méthode : copier les anomalies de l'analyse dans une table temporaire DuckDB, puis lire le fichier ligne par ligne avec, pour chaque ligne, la liste de ses colonnes en faute. Requête vérifiée sur DuckDB :

```sql
SELECT d.*, a.columns, a.messages
FROM ds d
LEFT JOIN (SELECT row_num,
                  list(DISTINCT column_name ORDER BY column_name) AS columns,
                  string_agg(DISTINCT message, ' ; ' ORDER BY message) AS messages
           FROM run_anomalies GROUP BY row_num) a
       ON a.row_num = d."_row_num"
ORDER BY d."_row_num"
LIMIT 1048575
```

**Explication.** Chaque ligne du fichier ressort avec `columns` (les colonnes à surligner, par exemple `['age', 'nom']`) et `messages` (le texte de la colonne « Anomalies »). L'écriture se fait avec `SXSSFWorkbook` de POI, qui n'écrit en mémoire que les 100 dernières lignes et vide le reste sur disque : un export de 500 000 lignes ne sature pas la mémoire. `LIMIT 1048575` respecte la limite d'Excel (1 048 576 lignes, en-tête compris) ; au-delà, l'onglet indique que le fichier a été tronqué et l'onglet Anomalies reste complet.

### 22.3 — Les écrans et les exports avec Claude Code

```text
Lot rapport et exports (CDC §5.6 RAP). RunReportService existe (étape 22 du guide). Crée le module reporting.
1. Page rapport /runs/{id} : en-tête (fichier, modèle, auteur, dates, nb lignes/colonnes, statut OK/Alerte/KO en
   badge de couleur, score global) ; graphique Chart.js en barres des scores par dimension (« Non mesurée » pour
   une dimension absente) ; tableau des règles (RAP-02 : contrôlées, conformes, anomalies, score, seuil, statut) ;
   tableau des scores par colonne ; bandeau des anomalies par sévérité. Chart.js chargé depuis le WebJar, script dans
   static/js (pas de script en ligne, politique CSP).
2. Onglet anomalies : RunReportService.anomalies paginé par 50, filtres règle et colonne, tri ; rechargement du
   tableau par HTMX sans recharger la page ; doublons affichés par groupe (« Groupe 3 : lignes 12, 45, 78 »).
3. Export Excel /runs/{id}/export?format=xlsx (RAP-05) avec SXSSFWorkbook : onglet Synthèse, onglet Anomalies
   (toutes, en flux), onglet Fichier d'origine : copier les anomalies du run dans une table temporaire DuckDB
   run_anomalies(row_num, column_name, message) puis la requête de l'étape 22.2 ; surligner en orange les cellules
   listées dans columns, colonne « Anomalies » = messages. Réponse en streaming (StreamingResponseBody),
   wb.close() dans un finally pour supprimer les fichiers temporaires de POI.
4. Export PDF /runs/{id}/export?format=pdf (RAP-06) avec OpenPDF : 1 à 2 pages, en-tête, scores par dimension,
   règles en KO et Alerte, 20 premières anomalies bloquantes.
5. Droits : tous les rôles consultent et exportent (CDC §4) ; chaque export publie un événement ReportExported (audit).
Tests : RunReportServiceIT sur une analyse créée par AnalysisRunnerIT ; un test qui ouvre l'export Excel produit
(XSSFWorkbook en lecture) et vérifie les 3 onglets et le surlignage d'une cellule connue (critère de recette).
```

**Vérification de fin de lot.** L'export Excel s'ouvre sans erreur et surligne les cellules en anomalie (critère de recette) ; un score global élevé avec une règle bloquante en échec affiche bien le statut KO.

## Étape 23 — Historique et comparaison

L'historique liste toutes les analyses (HIS-01), rouvre n'importe laquelle avec `RunReportService` (HIS-02), et compare deux analyses d'un même modèle (HIS-03) : la qualité d'un fichier s'est-elle améliorée depuis le mois dernier ? Tout repose sur deux requêtes. Branche conseillée : `feat/historique`.

**23.1 — Liste des analyses**

```sql
SELECT a.id, a.started_at, u.full_name AS lance_par, f.original_name AS fichier, t.name AS modele,
       a.status, a.result_status, a.global_score
FROM analysis_run a
JOIN app_user u ON u.id = a.launched_by
JOIN source_file f ON f.id = a.source_file_id
LEFT JOIN analysis_template t ON t.id = a.template_id
WHERE (CAST(? AS BIGINT) IS NULL OR a.template_id = ?)
ORDER BY a.started_at DESC
LIMIT ? OFFSET ?
```

**23.2 — Comparaison de deux analyses, règle par règle**

```sql
SELECT coalesce(o.rule_code, n.rule_code) AS regle,
       coalesce(n.name, o.name)           AS nom,
       o.score AS score_avant, n.score AS score_apres,
       n.score - o.score AS evolution,
       o.status AS statut_avant, n.status AS statut_apres
FROM (SELECT r.rule_code, r.name, rr.score, rr.status
      FROM rule_result rr JOIN dq_rule r ON r.id = rr.rule_id WHERE rr.run_id = ?) o
FULL JOIN
     (SELECT r.rule_code, r.name, rr.score, rr.status
      FROM rule_result rr JOIN dq_rule r ON r.id = rr.rule_id WHERE rr.run_id = ?) n
  ON n.rule_code = o.rule_code
ORDER BY evolution NULLS LAST
```

**Explication.**

- **La liste** est triée de la plus récente à la plus ancienne et paginée ; le filtre par modèle est facultatif grâce à `CAST(? AS BIGINT) IS NULL OR …`. L'index `idx_analysis_run_started` de la V1 la rend instantanée.
- **La comparaison** associe les règles des deux analyses par leur **code** (`rule_code`), pas par leur version : si la règle R-0007 est passée de la version 2 à la version 3 entre les deux analyses, elles restent comparées. `FULL JOIN` garde aussi une règle ajoutée ou retirée du modèle entre-temps (score « avant » ou « après » vide). Le tri par évolution montre d'abord les règles qui se sont le plus dégradées.
- **Limite à connaître** : comparer deux scores n'a de sens que si les règles sont restées comparables. Si une version a changé de seuil ou de paramètres, l'écran doit le signaler (numéros de version différents).

**Demande à Claude Code :**

```text
Lot historique (CDC §5.7). Requêtes SQL données à l'étape 23 du guide.
1. Écran « Historique » : tableau paginé des analyses (date, auteur, fichier, modèle, statut en badge, score global),
   filtre par modèle, clic → rapport de l'analyse (RunReportService, déjà capable de rouvrir une analyse ancienne).
2. Comparaison (HIS-03) : cocher deux analyses d'un même modèle → tableau règle par règle (score avant, après,
   évolution en vert ou rouge, statuts) avec la requête 23.2, et un graphique Chart.js en ligne de l'évolution
   du score global de ce modèle dans le temps. Signaler par une icône les règles dont la version diffère.
3. Refuser la comparaison de deux analyses de modèles différents, avec un message clair.
Test : HistoryServiceIT avec deux analyses d'un même modèle aux scores connus ; l'évolution calculée est juste.
```

**Vérification de fin de lot.** Une analyse passée se rouvre avec la version des règles utilisée à l'époque (critère de recette) ; la comparaison de deux analyses montre la bonne évolution pour chaque règle.

## Étape 24 — Double authentification

Après le mot de passe, l'utilisateur doit saisir un code à 6 chiffres : celui de Google ou Microsoft Authenticator (TOTP, MFA-01), ou à défaut un code reçu par e-mail (MFA-03). Ce lot est **obligatoire avant toute mise à disposition** : les fichiers analysés contiennent des données personnelles. Le cœur (codes, QR code, codes de secours, chiffrement du secret) est donné en code ; le branchement dans Spring Security et les écrans se font avec Claude Code, en suivant le principe ci-dessous. Ce code n'a pas pu être compilé ici (bibliothèque TOTP non disponible hors connexion) : `./mvnw verify` le validera. Branche conseillée : `feat/mfa`.

### 24.1 — Le principe

1. L'utilisateur saisit identifiant et mot de passe. S'ils sont bons, il n'obtient **pas** ses droits : seulement un droit provisoire `MFA_PENDING`, qui ne donne accès qu'aux pages `/mfa/**`.
2. Première connexion (MFA-02) : la page `/mfa/enroll` affiche un QR code ; l'utilisateur le scanne avec son application, saisit le premier code pour prouver que ça marche, et reçoit ses 10 codes de secours (MFA-05) à noter.
3. Connexions suivantes : la page `/mfa/verify` demande le code de l'application, ou propose « Recevoir un code par e-mail » (MFA-03), ou « Utiliser un code de secours ».
4. Code juste : la session reçoit les vrais rôles (ADMIN, ANALYST ou READER). Code faux 5 fois : compte bloqué temporairement (`locked_until`), débloquable par un administrateur (MFA-06).
5. Chaque étape est tracée dans le journal d'audit (MFA-07).

**Explication.** Le TOTP (« Time-based One-Time Password », RFC 6238) repose sur un secret partagé une seule fois, au moment du QR code, entre le serveur et le téléphone. Ensuite, chacun calcule de son côté un code à partir du secret et de l'heure, qui change toutes les 30 secondes. Rien ne circule sur le réseau à la connexion, sauf le code lui-même, inutilisable 30 secondes plus tard. Un mot de passe volé ne suffit donc plus.

### 24.2 — `security/domain/TotpService.java`

```java
package ci.cnam.dqanalyzer.security.domain;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.util.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Codes TOTP compatibles Google et Microsoft Authenticator (MFA-01, MFA-02). Le secret de chaque utilisateur est
 * chiffré (AES-GCM) avant d'être enregistré dans app_user.totp_secret : une copie volée de la base ne permet pas
 * de générer ses codes.
 */
@Component
public class TotpService {

    private final DefaultSecretGenerator secrets = new DefaultSecretGenerator();
    private final CodeVerifier verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
    private final TextEncryptor encryptor;

    public TotpService(@Value("${dq.mfa.encryption-key}") String key, @Value("${dq.mfa.encryption-salt}") String salt) {
        this.encryptor = Encryptors.delux(key, salt);
    }

    /** Nouveau secret, déjà chiffré, à enregistrer dans app_user.totp_secret. */
    public String newEncryptedSecret() {
        return encryptor.encrypt(secrets.generate());
    }

    /** Image du QR code (format data:image/png;base64,…) à afficher sur la page d'enrôlement. */
    public String qrCodeDataUri(String encryptedSecret, String accountLabel) {
        var data = new QrData.Builder()
                .label(accountLabel)
                .secret(encryptor.decrypt(encryptedSecret))
                .issuer("DQ-Analyzer CNAM")
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        try {
            var generator = new ZxingPngQrGenerator();
            return Utils.getDataUriForImage(generator.generate(data), generator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new IllegalStateException("QR code impossible à générer", e);
        }
    }

    /** Vrai si le code saisi correspond au secret, à 30 secondes près. */
    public boolean isValid(String encryptedSecret, String code) {
        return code != null && code.matches("\\d{6}") && verifier.isValidCode(encryptor.decrypt(encryptedSecret), code);
    }
}
```

**Explication.**

- **`DefaultSecretGenerator`** produit un secret aléatoire de 32 caractères, au format attendu par les applications d'authentification.
- **`QrData`** décrit le compte : `issuer` est le nom affiché dans l'application du téléphone (« DQ-Analyzer CNAM »), `label` le compte (l'identifiant de l'utilisateur). SHA1, 6 chiffres et 30 secondes sont les réglages que Google et Microsoft Authenticator comprennent tous les deux.
- **`Utils.getDataUriForImage`** transforme l'image en texte `data:image/png;base64,…`, directement utilisable dans `<img src="…">`. La politique CSP de la partie 1 autorise déjà les images `data:`.
- **`Encryptors.delux`** (Spring Security) chiffre le secret en AES-GCM avec une clé fournie par variable d'environnement. Le CDC ne le demandait pas explicitement, mais un secret TOTP en clair dans la base reviendrait à stocker un mot de passe en clair.
- **`isValid`** accepte le code de la période en cours et des périodes voisines : un décalage de quelques secondes entre l'horloge du téléphone et celle du serveur ne bloque pas l'utilisateur.

Ajoute dans `application.yml` :

```yaml
dq:
  mfa:
    encryption-key: ${DQ_MFA_KEY}          # longue phrase secrète, fournie par l'environnement
    encryption-salt: ${DQ_MFA_SALT}        # 16 caractères hexadécimaux : openssl rand -hex 8
    max-attempts: 5                        # MFA-06
    lock-minutes: 15
    email-code-minutes: 5                  # MFA-03
```

Et dans `application-dev.yml`, des valeurs locales jetables : `DQ_MFA_KEY` et `DQ_MFA_SALT` en clair sous `dq.mfa` (par exemple `encryption-key: dev-only-key-change-me` et `encryption-salt: 0123456789abcdef`). En production, ces deux valeurs vont dans le fichier `.env` du serveur (étape 14), jamais dans Git.

### 24.3 — Codes de secours : `security/domain/RecoveryCodes.java`

```java
package ci.cnam.dqanalyzer.security.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Génère les 10 codes de secours à usage unique (MFA-05), au format « 7KQ2-M9XA ». Ils ne sont montrés qu'une fois
 * à l'utilisateur ; la base ne garde que leur empreinte BCrypt (table mfa_recovery_code).
 */
public final class RecoveryCodes {

    /** Sans 0/O ni 1/I : impossible de les confondre en recopiant le code. */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    public static List<String> generate(int count) {
        var codes = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            var sb = new StringBuilder(9);
            for (int j = 0; j < 8; j++) {
                if (j == 4) {
                    sb.append('-');
                }
                sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    /** « 7kq2 m9xa » saisi par l'utilisateur → « 7KQ2-M9XA ». */
    public static String normalize(String input) {
        String compact = input.toUpperCase().replaceAll("[^0-9A-Z]", "");
        return compact.length() == 8 ? compact.substring(0, 4) + "-" + compact.substring(4) : compact;
    }
}
```

**Explication.** `SecureRandom` (et non `Random`) produit des valeurs imprévisibles, indispensables pour un code de sécurité. Chaque code est haché avec le même `PasswordEncoder` BCrypt que les mots de passe avant d'être enregistré ; à la saisie, on compare le code à chaque empreinte non utilisée avec `passwordEncoder.matches`, puis on remplit `used_at` : un code ne sert qu'une fois.

### 24.4 — Le branchement avec Claude Code

```text
Lot double authentification (CDC §5.8, MFA-01 à MFA-07). TotpService et RecoveryCodes existent (étape 24 du guide).
Propose un plan détaillé avant de coder, puis :
1. Spring Security : après un mot de passe valide, l'utilisateur ne reçoit que l'autorité MFA_PENDING
   (AuthenticationSuccessHandler qui remplace les autorités et garde les vrais rôles en session) ; dans
   SecurityConfig, /mfa/** exige MFA_PENDING, tout le reste exige un vrai rôle. Si la version de Spring Security
   du projet propose un support natif de l'authentification multifacteur, compare les deux approches et dis-moi
   laquelle tu recommandes avant de choisir.
2. /mfa/enroll (MFA-02) : si app_user.mfa_enrolled = false, générer le secret (TotpService), afficher le QR code,
   demander un premier code ; s'il est juste : mfa_enrolled = true, mfa_method = TOTP, générer 10 codes de secours
   (RecoveryCodes), enregistrer leurs empreintes BCrypt dans mfa_recovery_code, les afficher une seule fois.
3. /mfa/verify : code TOTP, ou bouton « code par e-mail » (MFA-03 : 6 chiffres aléatoires, empreinte + échéance
   5 min gardées en session, envoi par JavaMailSender, visible dans MailPit en dev), ou code de secours.
   Succès : remettre les vrais rôles dans le SecurityContext (et changer l'identifiant de session), remettre
   failed_mfa_attempts à 0. Échec : +1 ; à 5, locked_until = maintenant + 15 min (MFA-06).
4. Changement de méthode (MFA-04) après une nouvelle vérification ; écran admin « débloquer » et « réinitialiser
   la 2FA » d'un compte.
5. Événements d'audit pour chaque étape (MFA-07) : enrolled, verified, failed, locked, recovery_used, reset.
Tests : MfaFlowIT avec MockMvc : connexion → redirection /mfa/verify ; page protégée inaccessible avant le code ;
code TOTP calculé dans le test avec DefaultCodeGenerator → accès ; 5 codes faux → compte bloqué.
```

**Vérification de fin de lot.** La connexion exige le second facteur, avec Google Authenticator, Microsoft Authenticator et par e-mail (critère de recette) ; après 5 codes faux le compte est bloqué ; un code de secours ne fonctionne qu'une fois.

## Étape 25 — Journal d'audit, purge à 90 jours et LDAP

Trois sujets de sécurité qui vont ensemble : savoir **qui a fait quoi** (journal d'audit), **ne pas garder les données personnelles plus que nécessaire** (purge, SEC-04), et, si la DSI le demande, **se connecter avec son compte Windows** (LDAP, USR-03, optionnel). Les requêtes SQL ci-dessous ont été vérifiées ; le code Java n'a pas été compilé avec Maven. Branche conseillée : `feat/audit`.

### 25.1 — Le principe : des événements, un seul écrivain

Aucun module n'écrit lui-même dans `audit_log`. Chaque module **publie un événement** (« fichier importé », « analyse terminée », « rapport exporté ») et un seul composant, dans le module `audit`, les écoute et les enregistre.

**Explication.** C'est ce que Spring Modulith recommande : le module `importing` n'a pas à connaître le module `audit`, il annonce seulement ce qui s'est passé. Ajouter plus tard un nouvel abonné (par exemple un e-mail à l'administrateur quand une analyse échoue) ne touchera aucun module existant.

### 25.2 — L'événement commun : `shared/audit/AuditEvent.java`

```java
package ci.cnam.dqanalyzer.shared.audit;

import java.util.Map;

/**
 * Ce qui doit figurer au journal d'audit. Publié par n'importe quel module avec ApplicationEventPublisher ;
 * enregistré par le module audit. Le login et l'adresse IP sont ajoutés par l'écouteur, pas par l'émetteur.
 *
 * @param action     code court en majuscules : FILE_IMPORTED, ANALYSIS_COMPLETED, REPORT_EXPORTED, RULE_CHANGED…
 * @param objectType type de l'objet concerné (SOURCE_FILE, ANALYSIS_RUN, DQ_RULE…) ou null
 * @param objectId   identifiant de l'objet ou null
 * @param detail     informations utiles, sans donnée personnelle du fichier analysé
 */
public record AuditEvent(String action, String objectType, String objectId, Map<String, Object> detail) {

    public AuditEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }

    public static AuditEvent of(String action, String objectType, Object objectId) {
        return new AuditEvent(action, objectType, objectId == null ? null : objectId.toString(), Map.of());
    }
}
```

**Explication.** L'événement est dans `shared`, le seul module ouvert à tous : chaque module peut le publier sans dépendre d'`audit`. Le `detail` ne doit **jamais** contenir de valeur de cellule (un nom, un n° de sécurité sociale) : le journal est conservé plus longtemps que les anomalies, il ne doit pas devenir une copie des données.

Pour publier, depuis n'importe quel service :

```java
events.publishEvent(new AuditEvent("ANALYSIS_COMPLETED", "ANALYSIS_RUN", run.id().toString(),
        Map.of("status", status.name(), "score", globalScore)));
```

où `events` est un `ApplicationEventPublisher` reçu par le constructeur.

### 25.3 — L'écrivain : `audit/AuditListener.java`

```java
package ci.cnam.dqanalyzer.audit;

import ci.cnam.dqanalyzer.shared.audit.AuditEvent;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/** Enregistre dans audit_log les événements métier et les connexions (TRA-03, MFA-07). */
@Component
class AuditListener {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    AuditListener(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @EventListener
    void on(AuditEvent event) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        write(auth == null ? null : auth.getName(), event.action(), event.objectType(), event.objectId(), event.detail());
    }

    @EventListener
    void onLogin(AuthenticationSuccessEvent event) {
        write(event.getAuthentication().getName(), "LOGIN_PASSWORD_OK", "APP_USER", null, Map.of());
    }

    @EventListener
    void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        write(event.getAuthentication().getName(), "LOGIN_FAILED", "APP_USER", null,
                Map.of("reason", event.getException().getClass().getSimpleName()));
    }

    private void write(String login, String action, String objectType, String objectId, Map<String, Object> detail) {
        jdbc.sql("""
                INSERT INTO audit_log (user_login, action, object_type, object_id, ip_address, detail)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
                """)
                .params(login, action, objectType, objectId, clientIp(), json.writeValueAsString(detail))
                .update();
    }

    /** Adresse du poste. Derrière nginx (étape 14), elle arrive dans X-Forwarded-For, traité par Spring Boot. */
    private static String clientIp() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest().getRemoteAddr()
                : null;
    }
}
```

**Explication.**

- **`@EventListener`** (synchrone) : la ligne d'audit est écrite **dans la même transaction** que l'action. Si l'import échoue et est annulé, sa ligne d'audit l'est aussi ; si l'écriture d'audit échoue, l'action est annulée. Pour un journal de traçabilité, c'est le comportement voulu.
- **`AuthenticationSuccessEvent` / `AbstractAuthenticationFailureEvent`** sont publiés par Spring Security à chaque tentative de mot de passe ; Spring Boot configure ce mécanisme tout seul. Les événements de double authentification (étape 24) sont, eux, des `AuditEvent` publiés par ton code MFA.
- **`RequestContextHolder`** donne la requête HTTP en cours, donc l'adresse IP. Pendant une analyse en tâche de fond (`@Async`), il n'y a pas de requête : l'IP vaut `null`, et le login aussi si tu ne recopies pas le contexte de sécurité. Pour que « ANALYSIS\_COMPLETED » porte le nom de l'analyste, publie l'événement avec le login dans `detail`, ou configure `DelegatingSecurityContextAsyncTaskExecutor` dans `AsyncConfig`.
- **L'adresse réelle derrière nginx** : ajoute `server.forward-headers-strategy: native` dans `application-prod.yml`, sinon toutes les lignes porteront l'adresse du proxy (127.0.0.1).

### 25.4 — La purge à 90 jours : `execution/RetentionJob.java`

Le CDC (SEC-04) demande de supprimer les données détaillées après 90 jours tout en **gardant les scores** pour l'historique (étape 23).

```java
package ci.cnam.dqanalyzer.execution;

import ci.cnam.dqanalyzer.shared.audit.AuditEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Chaque nuit : supprime anomalies et fichiers de plus de N jours ; garde analyses, scores et journal (SEC-04). */
@Component
class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final int days;

    RetentionJob(JdbcClient jdbc, ApplicationEventPublisher events, @Value("${dq.retention.days:90}") int days) {
        this.jdbc = jdbc;
        this.events = events;
        this.days = days;
    }

    @Scheduled(cron = "${dq.retention.cron:0 30 2 * * *}")
    @Transactional
    public void purge() {
        int anomalies = jdbc.sql("""
                DELETE FROM anomaly a USING analysis_run r
                WHERE a.run_id = r.id AND r.started_at < now() - make_interval(days => ?)
                """).param(days).update();

        var files = jdbc.sql("""
                SELECT id, storage_path FROM source_file
                WHERE purged_at IS NULL AND uploaded_at < now() - make_interval(days => ?)
                """).param(days).query((rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)}).list();

        for (Object[] file : files) {
            deleteQuietly((String) file[1]);
            jdbc.sql("UPDATE source_file SET purged_at = now(), storage_path = NULL WHERE id = ?")
                    .param(file[0]).update();
        }
        log.info("Purge {} jours : {} anomalies, {} fichiers", days, anomalies, files.size());
        events.publishEvent(new AuditEvent("RETENTION_PURGE", null, null,
                Map.of("days", days, "anomalies", anomalies, "files", files.size())));
    }

    private static void deleteQuietly(String storagePath) {
        if (storagePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(storagePath));
            Files.deleteIfExists(Path.of(storagePath + ".wal"));
        } catch (IOException e) {
            log.warn("Fichier non supprimé : {}", storagePath, e);
        }
    }
}
```

**Explication.**

- **Ce qui est supprimé** : les lignes d'`anomaly` (elles contiennent les valeurs des cellules, donc des données personnelles) et le fichier DuckDB du fichier importé (`storage/files/<id>.duckdb`, plus son éventuel journal `.wal`).
- **Ce qui est gardé** : `analysis_run`, `rule_result` (les scores par règle), `source_file` (nom, taille, empreinte SHA-256, avec `purged_at` rempli) et `audit_log`. L'historique et la comparaison de l'étape 23 continuent de fonctionner ; seul le détail des lignes en anomalie n'est plus consultable, et l'écran de résultat doit l'indiquer (« Détail purgé le … »).
- **`make_interval(days => ?)`** : la durée est un paramètre, pas un texte collé dans la requête.
- **`cron = "0 30 2 * * *"`** : tous les jours à 2 h 30 (format Spring à 6 champs : seconde, minute, heure, jour, mois, jour de semaine). Il faut `@EnableScheduling` sur une classe de configuration : ajoute-le à côté de `@EnableAsync` dans `shared/config/AsyncConfig`.
- **Fichier puis base, dans une transaction** : si la suppression du fichier échoue, on le note et on continue ; une suppression de fichier ne peut de toute façon pas être annulée par la transaction.
- **Le journal d'audit n'est pas purgé** par cette tâche : sa durée de conservation (souvent un an ou plus) est à fixer avec ton chef et le responsable sécurité. Note-la dans le cahier des charges.

Pour tester sans attendre 90 jours : un test d'intégration (Testcontainers) qui insère une analyse avec `started_at = now() - interval '91 days'` et ses anomalies, appelle `purge()`, et vérifie que les anomalies ont disparu et que `rule_result` est intact.

### 25.5 — L'écran du journal (administrateur)

Requête de l'écran `/admin/audit`, filtrable par utilisateur et par date, 50 lignes par page :

```sql
SELECT occurred_at, user_login, action, object_type, object_id, ip_address
FROM audit_log
WHERE (CAST(? AS VARCHAR) IS NULL OR user_login = ?)
  AND occurred_at >= ?
ORDER BY occurred_at DESC
LIMIT 50 OFFSET ?
```

Ajoute un index pour que l'écran reste rapide quand le journal grossit, dans une nouvelle migration `V4__audit_index.sql` (le numéro V3 est déjà pris par la colonne `anomalies_truncated` de l'étape 21 ; prends le premier numéro libre) :

```sql
CREATE INDEX idx_audit_log_occurred ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_user ON audit_log (user_login, occurred_at DESC);
```

**Explication.** Ne modifie jamais une migration déjà appliquée (`V1`, `V2`, `V3`) : Flyway vérifie leur empreinte et refuserait de démarrer. Toute évolution du schéma passe par un nouveau fichier au numéro suivant.

### 25.6 — LDAP / Active Directory (optionnel, USR-03)

À faire **seulement si la DSI le demande** et te donne : l'adresse du serveur (`ldaps://ad.cnam.local:636`), le domaine, et un compte de test.

**Explication.** Avec LDAP, le mot de passe est vérifié par l'annuaire de la CNAM et non plus par `app_user.password_hash`. Le reste ne change pas : la ligne `app_user` existe toujours (rôle, double authentification, journal), elle est simplement créée au premier login, avec le rôle READER, et l'administrateur lui donne ensuite le bon rôle.

```text
Lot LDAP (USR-03, optionnel). Ajoute spring-security-ldap. Active un ActiveDirectoryLdapAuthenticationProvider
uniquement quand dq.ldap.enabled=true (propriétés dq.ldap.url, dq.ldap.domain), en plus du provider base de
données (les comptes locaux restent utilisables, au moins le compte admin de secours). Au premier login LDAP réussi,
crée la ligne app_user (login, nom, e-mail lus dans l'annuaire, rôle READER, password_hash = '{noop}LDAP' jamais
utilisable). Le rôle vient toujours de app_user, pas des groupes AD. La double authentification de l'étape 24
s'applique aussi aux comptes LDAP. Test : avec dq.ldap.enabled=false, rien ne change (tests existants verts).
```

**Vérification de fin de lot.** Chaque import, analyse, export, modification de règle, connexion et étape de double authentification apparaît dans `/admin/audit` avec le login et l'adresse IP réelle ; le test de purge passe ; après purge, l'historique affiche toujours les scores.

## Étape 26 — Performance, recette et livrables

Dernier lot : prouver que l'outil tient la charge (PERF-01), dérouler la recette avec ton chef sur les 10 critères d'acceptation du CDC, et remettre les documents. Branche conseillée : `release/v1.0`.

### 26.1 — Fabriquer les fichiers de test

PERF-01 demande : **100 000 lignes × 30 colonnes avec 20 règles en moins de 60 s**, et **un CSV de 2 Go en moins de 15 min** (hors doublons approchés UNI-04). Il faut des fichiers de cette taille, sans données réelles : on les génère avec DuckDB.

Installe la ligne de commande DuckDB (un seul exécutable, téléchargeable sur duckdb.org) et crée `perf/generate.sql` (ce dossier n'est pas embarqué dans l'application ; ajoute `perf/*.csv` à `.gitignore`) :

```sql
-- perf/generate.sql : fichier de test PERF-01, n lignes x 30 colonnes, avec des anomalies connues.
CREATE OR REPLACE TABLE perf AS
  SELECT
    'CI' || lpad(CAST(i % 900000 AS VARCHAR), 11, '0')                     AS num_secu,
    CASE WHEN i % 50 = 0 THEN '' ELSE 'NOM' || (i % 20000) END             AS nom,
    'PRENOM' || (i % 3000)                                                  AS prenom,
    CASE WHEN i % 2 = 0 THEN 'M' WHEN i % 997 = 0 THEN 'X' ELSE 'F' END    AS sexe,
    strftime(DATE '1940-01-01' + CAST(i % 30000 AS INTEGER), '%d/%m/%Y')    AS date_naiss,
    CASE WHEN i % 113 = 0 THEN '150' ELSE CAST(i % 90 AS VARCHAR) END       AS age,
    '07' || lpad(CAST(i % 100000000 AS VARCHAR), 8, '0')                    AS tel,
    'user' || i || CASE WHEN i % 211 = 0 THEN '@' ELSE '@cnam.ci' END       AS email,
    lpad(CAST(i % 120 AS VARCHAR), 3, '0')                                  AS code_commune,
    CASE WHEN i % 3 = 0 THEN 'Assuré' ELSE 'Ayant droit' END                AS statut,
    'valeur_' || (i % 91) AS col_11,
    'valeur_' || (i % 92) AS col_12,
    'valeur_' || (i % 93) AS col_13,
    'valeur_' || (i % 94) AS col_14,
    'valeur_' || (i % 95) AS col_15,
    'valeur_' || (i % 96) AS col_16,
    'valeur_' || (i % 97) AS col_17,
    'valeur_' || (i % 98) AS col_18,
    'valeur_' || (i % 99) AS col_19,
    'valeur_' || (i % 100) AS col_20,
    'valeur_' || (i % 101) AS col_21,
    'valeur_' || (i % 102) AS col_22,
    'valeur_' || (i % 103) AS col_23,
    'valeur_' || (i % 104) AS col_24,
    'valeur_' || (i % 105) AS col_25,
    'valeur_' || (i % 106) AS col_26,
    'valeur_' || (i % 107) AS col_27,
    'valeur_' || (i % 108) AS col_28,
    'valeur_' || (i % 109) AS col_29,
    'valeur_' || (i % 110) AS col_30
  FROM range(1, getvariable('n') + 1) t(i);
COPY perf TO 'perf.csv' (HEADER, DELIMITER ';');
```

Puis, depuis le dossier `perf` :

```bash
duckdb -c "SET VARIABLE n = 100000" -c ".read generate.sql" && mv perf.csv perf_100k.csv     # ~29 Mo
duckdb -c "SET VARIABLE n = 7000000" -c ".read generate.sql" && mv perf.csv perf_2go.csv    # ~2 Go
```

Sous Windows, dans le terminal PowerShell d'IntelliJ, lance les deux parties de chaque ligne séparément (d'abord `duckdb …`, puis `Rename-Item perf.csv perf_100k.csv`) : l'ancien PowerShell 5 livré avec Windows ne comprend pas `&&`. Un clic droit sur `generate.sql` dans IntelliJ propose aussi de l'exécuter, mais seulement si une source de données DuckDB est configurée (Ultimate) ; la ligne de commande est plus simple.

**Explication.**

- **`range(1, n + 1)`** produit les nombres 1 à n ; chaque colonne est calculée à partir de ce numéro `i`. Le fichier est donc **toujours identique** : tu peux refaire la mesure après une optimisation et comparer.
- **Les anomalies sont placées exprès et se comptent à l'avance** : un nom vide toutes les 50 lignes (CMP-01 : 2 000 anomalies sur 100 000), un âge à 150 toutes les 113 lignes (VAL-04 : 884), un e-mail invalide toutes les 211 (VAL-02), un sexe « X » (VAL-05), des doublons de `num_secu` puisque le n° repart à zéro tous les 900 000 (UNI-01, seulement dans le gros fichier), et des codes commune de 000 à 119 dont une partie n'existe pas dans un référentiel de 100 communes (INT-01). Si l'outil ne trouve pas exactement ces nombres, c'est un bug, pas une question de performance.
- **Le point-virgule** comme séparateur et les dates en jj/mm/aaaa ressemblent aux exports réels de la CNAM.
- **7 millions de lignes** font environ 2 Go avec ces colonnes (29 Mo pour 100 000 lignes, mesuré). Générer le fichier prend moins d'une minute.

### 26.2 — Mesurer

La mesure se fait **sur le serveur de recette** (étape 14), pas sur ton PC : c'est là que la performance compte. Avant de commencer, note dans le cahier de recette le processeur, la mémoire et le disque du serveur (`nproc`, `free -h`, `df -h`).

1. Crée un modèle « PERF » avec **20 règles** réparties sur les colonnes : CMP-01 sur `nom` et `num_secu`, CMP-02 sur `date_naiss`, VAL-01 entier sur `age`, VAL-02 téléphone et e-mail, VAL-04 sur `age`, VAL-05 sur `sexe` et `statut`, VAL-06 sur `date_naiss`, VAL-03 sur `num_secu`, INT-01 sur `code_commune`, ACT-02 sur `date_naiss`, UNI-01 sur `num_secu`, UNI-02 sur `nom` + `date_naiss`, UNI-03 sur `nom`, et 4 règles VAL-03 ou VAL-07 sur `col_11` à `col_14`. Pas d'UNI-04 (exclu par PERF-01).
2. Importe `perf_100k.csv`, lance l'analyse avec le modèle, et lis la durée : `analysis_run.finished_at - started_at`. Objectif : **moins de 60 s**. Refais-le 3 fois et garde la valeur la plus haute.
3. Même chose avec `perf_2go.csv`, en mesurant séparément l'envoi + l'import (heure d'arrivée de `source_file`) et l'analyse. Objectif : **moins de 15 min** pour l'analyse. Pendant l'envoi, coupe le Wi-Fi 30 secondes puis rebranche : l'envoi par morceaux doit reprendre là où il s'était arrêté (critère de recette n° 8).
4. Pendant l'analyse du gros fichier, regarde la mémoire du conteneur : `podman stats dq-app`. Fixe d'abord une limite au service app (ligne mem\_limit: 8g, à adapter à la mémoire du serveur) ; la consommation doit rester sous cette limite, définie dans `compose.prod.yaml`.

Requête pour lire les durées d'un coup :

```sql
SELECT r.id, f.original_name, f.row_count, r.status,
       r.finished_at - r.started_at AS duree
FROM analysis_run r JOIN source_file f ON f.id = r.source_file_id
ORDER BY r.id DESC LIMIT 10;
```

**Explication.** On garde la valeur la plus haute des trois essais parce que l'objectif est une promesse faite aux utilisateurs : il doit être tenu même quand le serveur est moins disponible.

**Si c'est trop lent**, dans cet ordre (mesure après chaque changement, un seul à la fois) :

1. **Trouver la règle lente** : ajoute un log de la durée de chaque règle dans `AnalysisRunner` (`System.nanoTime()` avant et après `evaluate`). En général, une ou deux règles prennent l'essentiel du temps.
2. **Mémoire et threads de DuckDB** : dans `DuckDbWorkspace.open()`, `SET memory_limit = '4GB'` (environ la moitié de la mémoire du conteneur) et `SET threads = <nombre de cœurs>`. Par défaut DuckDB prend 80 % de la mémoire de la machine, pas du conteneur, ce qui peut faire tuer le conteneur.
3. **Limiter les anomalies enregistrées** : si une règle trouve 2 millions d'anomalies, c'est leur insertion dans PostgreSQL qui coûte, pas leur détection. `JdbcAnomalySink` tronque déjà au-delà du plafond (étape 21) ; vérifie que l'insertion se fait bien par lots (`batchUpdate`) de 1 000 lignes.
4. **Disque** : le fichier `.duckdb` doit être sur un disque local rapide, pas sur un partage réseau.

### 26.3 — La recette : les 10 critères du CDC

Copie ce tableau dans le cahier de recette (26.4) et remplis-le avec ton chef. Pour chaque critère : ce qui le prouve, et où dans le guide il a été préparé.

| N° | Critère du CDC | Comment le prouver | Préparé à |
| --- | --- | --- | --- |
| 1 | Les 23 types de règles détectent chaque anomalie attendue | `RuleCatalogueTest` vert (28 cas) dans le rapport de `./mvnw verify`, plus une démonstration à l'écran sur `catalogue_regles.csv` | Étapes 9 et 16 |
| 2 | Les scores correspondent au calcul manuel | `AnalysisRunnerIT` vert ; montrer le calcul à la main (commentaire du test) à côté du rapport | Étapes 9 et 21 |
| 3 | Analyse complète sans code, en moins de 5 min, fichier déjà associé à un modèle | Un analyste (pas toi) importe un fichier et lance l'analyse avec un chronomètre | Étapes 17 à 22 |
| 4 | L'export Excel s'ouvre sans erreur et surligne les anomalies | Ouvrir l'export dans Excel (pas seulement LibreOffice) | Étape 22 |
| 5 | PERF-01 atteint | Mesures de 26.2 reportées avec la configuration du serveur | Étape 26 |
| 6 | Un Lecteur ne peut ni créer ni modifier une règle | Test d'autorisation vert (403 sur POST /rules avec le rôle READER) + essai à l'écran avec le compte de démo lecteur | Étapes 8 et 19 |
| 7 | Une analyse passée se rouvre avec la version des règles d'époque | Modifier une règle, rouvrir une ancienne analyse : elle affiche l'ancien paramétrage et la version | Étapes 19 et 23 |
| 8 | CSV de 2 Go importé après coupure réseau, analysé dans le délai | Essai de 26.2 point 3 | Étapes 17 et 26 |
| 9 | Chaque format du tableau 5.1 bis est importé | Un fichier d'exemple par format (CSV, TXT à largeur fixe ou délimité, XLSX, XLS, JSON, Parquet…) dans `src/test/resources/datasets/formats/`, test d'import paramétré vert | Étape 17 |
| 10 | Connexion avec second facteur : Google, Microsoft Authenticator, e-mail | Trois connexions devant ton chef, une par méthode | Étape 24 |

**Explication.** Chaque ligne a une **preuve automatique** (un test vert, rejouable à chaque version) quand c'est possible, et une **preuve humaine** (quelqu'un d'autre que toi le fait devant témoin) pour ce qui touche à l'usage. Le critère 3 doit être fait par un analyste qui n'a pas développé l'outil : toi, tu connais déjà tous les écrans. Ajoute en fin de tableau une ligne « Réserves » pour ce qui est accepté avec un défaut mineur à corriger, avec une date.

Avant la recette, ferme aussi les **questions ouvertes du CDC** (formats CNAM/CNPS, serveur, annuaire, SMTP, espace disque) et la règle « 0 ligne contrôlée = statut OK » (étape 9) : une recette ne se signe pas sur une question sans réponse.

### 26.4 — Les livrables

Quatre documents, à écrire avec l'aide de Claude Code (il connaît le code) puis à relire toi-même :

| Document | Pour qui | Contenu | Où |
| --- | --- | --- | --- |
| `README.md` | Tout développeur qui ouvre le dépôt | Ce que fait l'outil en 3 lignes, prérequis, lancer en dev (`podman compose up`), lancer les tests, structure des modules, lien vers les autres documents | Racine du dépôt |
| Manuel utilisateur | Analystes et lecteurs | Se connecter (2FA), importer, créer une règle (une fiche par type de règle avec un exemple CNAM), créer un modèle, lancer une analyse, lire le rapport et les scores, exporter, historique. Avec des captures d'écran | `docs/manuel-utilisateur.md` |
| Guide développeur et d'exploitation | Toi dans un an, ton successeur, l'exploitant | Architecture (la carte de la partie 1), ajouter un type de règle, migrations Flyway, installation sur serveur (étape 14), sauvegarde et restauration de la base, renouvellement du certificat, variables d'environnement, purge | `docs/guide-developpeur.md` |
| Cahier de recette | Ton chef, qui signe | Le tableau de 26.3 rempli, les mesures de 26.2, les réserves, date et signatures | `docs/cahier-recette.md`, exporté en PDF pour la signature |

```text
Lot livrables. Rédige docs/guide-developpeur.md à partir du code réel du dépôt (pas de suppositions) :
architecture des modules et leurs dépendances (vérifie avec le test Modulith et sa documentation générée),
procédure pas à pas pour ajouter un nouveau type de règle (fichiers à créer, test à ajouter dans
RuleCatalogueTest), migrations Flyway, installation sur serveur avec compose.prod.yaml, sauvegarde avec
pg_dump depuis le conteneur postgres et restauration, variables d'environnement (tableau : nom, rôle, exemple).
Puis mets à jour README.md. Signale-moi tout écart entre le guide et ce que tu trouves dans le code.
```

**Explication.** La sauvegarde est le point le plus souvent oublié : sans elle, une panne du serveur efface toutes les règles et tout l'historique. Dans le guide d'exploitation, la commande type est `podman exec dq-postgres pg_dump -U postgres -Fc dqanalyzer > sauvegarde_$(date +%F).dump`, planifiée chaque nuit, avec un essai de restauration au moins une fois avant la mise en production. Une sauvegarde jamais restaurée n'est pas une sauvegarde.

### 26.5 — Mettre la version 1.0 en production

1. Tous les tests verts : `./mvnw verify` (avec la couverture JaCoCo à 70 % sur `rules` et `execution`).
2. Cahier de recette signé.
3. Fixe la version dans `pom.xml` (`1.0.0` au lieu de `0.0.1-SNAPSHOT`), commit, puis étiquette : `git tag -a v1.0.0 -m "DQ-Analyzer V1"` et `git push --tags`.
4. Construis l'image à partir de l'étiquette et déploie sur le serveur comme à l'étape 14 ; les migrations Flyway s'appliquent seules au démarrage.
5. Change les mots de passe des comptes de démonstration ou supprime-les : `afterMigrate.sql` n'est chargé qu'avec le profil `dev`, vérifie qu'aucun compte de démo n'existe en production.

**Vérification de fin de lot.** Les 10 critères sont cochés ou en réserve datée, les mesures PERF-01 sont dans le cahier, les quatre documents sont dans `docs/`, et l'étiquette `v1.0.0` existe dans Git.
