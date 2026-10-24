# DQ-Analyzer

Outil web d'analyse de la qualité des données Master Data (Service Gouvernance des Données, DSI CNAM).
L'analyste charge un fichier (≤ 2 Go), applique un jeu de règles, obtient un score par règle / dimension / colonne / global et la liste des anomalies.
Référence fonctionnelle complète : `docs/CAHIER_DES_CHARGES_V1.md`. Les ID d'exigence (IMP-01, REG-03, MFA-05…) et les codes de règle (CMP-01, UNI-04…) qui y figurent sont la référence commune : cite-les dans le code, les tests et les commits.

## Stack

- Java 21, Spring Boot 4.1, Maven (wrapper `../mvnw`)
- UI : Thymeleaf + Bootstrap 5 + HTMX, Chart.js. Pas de front séparé, pas de build JS.
- Base applicative : PostgreSQL, schéma géré par Flyway (`../src/main/resources/db/migration`)
- Moteur d'analyse : DuckDB embarqué (JDBC), un fichier `.duckdb` sur disque par analyse
- Lecture fichiers : Apache POI (streaming), Commons CSV, StAX, Jackson streaming, Apache Tika
- Export : Apache POI, OpenPDF. Similarité : Commons Text / fonctions DuckDB
- Sécurité : Spring Security, BCrypt, TOTP (dev.samstevens.totp), ZXing, Spring Mail
- Tests : JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL)
- Conteneurs : podman (`../compose.yaml` à la racine)

## Commandes

```bash
podman compose up -d                 # PostgreSQL local (+ MailPit pour les e-mails 2FA)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
./mvnw test                          # tests unitaires
./mvnw test -Dtest=Uni02EvaluatorTest   # un seul test : à privilégier pendant le dev
./mvnw verify                        # tests + intégration + couverture + vérif. des modules
./mvnw spotless:apply                # formatage (à lancer avant chaque commit)
```

Avant de déclarer une tâche terminée : `./mvnw spotless:apply verify` doit passer.

## Architecture

Monolithe modulaire. Package racine `ci.cnam.dqanalyzer`, un sous-package par module :

| Module | Rôle |
|---|---|
| `importing` | Upload par morceaux (10 Mo, reprise), détection format/encodage, lecture en flux → table DuckDB |
| `profiling` | Profil des colonnes (PRF-*) |
| `rules` | Définitions de règles, versions, catalogue des `RuleEvaluator` |
| `templates` | Modèles d'analyse, association des colonnes |
| `reference` | Tables de référence versionnées |
| `execution` | Lancement asynchrone, moteur, calcul des scores (§7 du CDC) |
| `reporting` | Rapport écran, export Excel / PDF |
| `history` | Historique et comparaison d'analyses |
| `security` | Utilisateurs, rôles, 2FA |
| `audit` | Journal d'audit (événements Spring publiés par les autres modules) |
| `shared` | Types transverses uniquement (pas de logique métier) |

Structure interne d'un module : `api/` (contrôleurs REST + vues Thymeleaf), `domain/` (entités, records, logique), `infra/` (repositories, accès DuckDB, fichiers). Les autres modules n'utilisent que les classes publiques à la racine du module ; jamais `domain/` ou `infra/` d'un autre module. Les dépendances entre modules sont vérifiées par un test Spring Modulith (`ModularityTest`).

### Moteur de règles (cœur du projet)

- Un type de règle = une classe `@Component` qui implémente `RuleEvaluator` (dans `rules/evaluators/`). Le moteur les découvre par injection ; **ne jamais modifier le moteur pour ajouter un type** (MAI-01).
- Paramètres d'une règle : JSON, décrits par `paramSchema()` qui sert aussi à générer le formulaire.
- Chaque évaluateur traduit ses paramètres en SQL DuckDB qui (1) compte contrôlées / conformes, (2) extrait les anomalies avec le n° de ligne d'origine (`_row_num`).
- Score règle = conformes / contrôlées × 100. Pondération sévérité Bloquante 3, Majeure 2, Mineure 1. Statut global KO dès qu'une règle Bloquante est KO. Toute la logique de score vit dans `execution/domain/Scoring` et nulle part ailleurs.
- Un résultat référence la version exacte de chaque règle et table de référence (TRA-02).

## Règles impératives

**Sécurité**
- Aucune valeur saisie par l'utilisateur n'est concaténée dans du SQL. Valeurs → paramètres `?`. Noms de colonnes → `DuckDbIdentifiers.quote()` après vérification qu'ils existent dans la table importée.
- Conditions et expressions utilisateur (CMP-03, COH-02, COH-03) : uniquement via le langage contrôlé de `rules/expression`, jamais de SQL libre.
- Chaque endpoint et chaque écran déclare son autorisation (`@PreAuthorize`). Rôles : `ADMIN`, `ANALYST`, `READER`. Tout nouvel endpoint a un test qui prouve qu'un `READER` ne peut pas écrire.
- Ne jamais logger de données personnelles (valeurs de cellules, n° de sécurité sociale, codes 2FA, secrets TOTP).
- Fichiers importés : vérifier extension + type MIME réel (Tika) + taille.

**Volumétrie**
- Jamais de chargement intégral d'un fichier en mémoire : lecture en flux, insertion par lots dans DuckDB (appender). Pas de `List<Row>` de tout le fichier, pas de `XSSFWorkbook` (utiliser le mode streaming).
- Anomalies : toujours paginées (API et écrans).

**Base de données**
- Toute modification du schéma PostgreSQL = nouvelle migration Flyway `V<n>__description.sql`. Ne jamais modifier une migration déjà commitée.
- `spring.jpa.hibernate.ddl-auto=validate`, jamais `update`.

**Code**
- Identifiants (classes, méthodes, variables) en anglais. Textes visibles par l'utilisateur en français, dans `messages.properties`, jamais en dur dans le code ou les templates.
- Messages d'anomalie compréhensibles par un non-informaticien (ERG-02).
- Records pour les DTO et objets valeur ; injection par constructeur ; pas de `Optional` en paramètre ni en champ.
- Traitements longs (import, analyse) en `@Async` avec suivi de progression ; le contrôleur rend la main immédiatement.
- Pas de nouvelle dépendance Maven sans le demander d'abord.

## Tests

- Chaque `RuleEvaluator` a un test avec un petit jeu de données à anomalies connues (`src/test/resources/datasets/`) : vérifier le nombre de lignes contrôlées, conformes, et les n° de ligne en anomalie.
- Pour un nouveau type de règle : écrire le test d'abord, le voir échouer, puis implémenter.
- Tests d'intégration (`*IT.java`) avec Testcontainers, jamais une base partagée.
- Couverture ≥ 70 % sur `rules` et `execution` (JaCoCo, bloquant dans `verify`).

## Méthode de travail

- Tâche qui touche plus de 2 fichiers ou un nouveau module : proposer un plan court (fichiers touchés, étapes) avant de coder.
- Avancer par petits incréments qui compilent et passent les tests ; un incrément = un commit.
- Commits : Conventional Commits, en français, avec l'ID d'exigence si pertinent. Ex. `feat(rules): évaluateur UNI-02 clé composée unique`.
- En cas de doute sur une règle métier, se référer au CDC ; s'il ne tranche pas, poser la question plutôt que d'inventer.
- Questions ouvertes du CDC (formats d'identifiants CNAM/CNPS, SMTP, serveur cible) : ne pas coder de valeur en dur, prévoir un paramètre de configuration.

## Configuration

- Profils : `dev` (Postgres podman, MailPit, 2FA par e-mail visible dans MailPit), `test`, `prod`.
- Rien de sensible dans le dépôt : secrets via variables d'environnement (`DQ_DB_PASSWORD`, `DQ_MAIL_*`…). `application-dev.yml` ne contient que des valeurs locales jetables.
- Répertoire de travail des fichiers et bases DuckDB : `dq.storage.path` (prévoir 3 × taille max par analyse simultanée).
