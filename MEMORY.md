# MEMORY — contexte permanent DQ-Analyzer

Fichier de mémoire du travail fait avec Claude Code. À relire en début de session,
à compléter en fin de session (une ligne par étape terminée, décision ou piège rencontré).

## Le projet en bref

DQ-Analyzer : outil web d'analyse de la qualité des données Master Data
(Service Gouvernance des Données, DSI CNAM). L'analyste charge un fichier (≤ 2 Go),
applique un jeu de règles, obtient un score par règle / dimension / colonne / global
et la liste des anomalies.

- Référence fonctionnelle : `docs/CAHIER_DES_CHARGES_V1.md` (ID d'exigence : IMP-01, PRF-02, REG-03…)
- Plan de réalisation : `docs/Partie 2 — Réalisation des lots.md` (étapes 15 à 26)
- Règles de code impératives : `docs/CLAUDE.md` — à respecter sans exception
- Stack : Java 21, Spring Boot 4.1, Maven wrapper, Thymeleaf + Bootstrap 5 + HTMX + Chart.js,
  PostgreSQL + Flyway, DuckDB embarqué pour le moteur, Spring Modulith
- Avant de déclarer une tâche terminée : `./mvnw spotless:apply verify` doit passer

Modules (`ci.cnam.dqanalyzer.*`) : importing, profiling, rules, templates, reference,
execution, reporting, history, security, audit, shared. Un module n'utilise que les classes
publiques à la racine des autres modules, jamais leur `domain/` ni leur `infra/`
(vérifié par `ModularityTest`).

## État d'avancement

| Étape | Sujet | État |
|---|---|---|
| 15 | Intégration continue (`./mvnw verify` sur push et PR) | fait |
| 16 | Catalogue des 23 types de règles + langage contrôlé | fait |
| 17 | Import de fichiers (CSV, Excel, ODS, Parquet, XML, JSON, largeur fixe, zip), envoi par morceaux de 10 Mo avec reprise, chargement DuckDB en flux | fait |
| 18 | Profilage des colonnes + écran « Profil du fichier » avec suggestions de règles (PRF-01 à PRF-04) | fait |
| 19 | Gestion des règles et des modèles | fait (REG-01 à REG-06, MOD-01 à MOD-03) |
| 20 | Tables de référence | à faire |
| 21 | Exécution des analyses | à faire |
| 22 | Rapport, exports Excel et PDF | à faire |
| 23 | Historique et comparaison | à faire |
| 24 | Double authentification (TOTP + codes de secours) | à faire |
| 25 | Journal d'audit, purge 90 jours, LDAP | à faire |
| 26 | Performance, recette, livrables | à faire |

Branche courante : `feat/regles-modeles` (créée depuis `feat/profilage`). Branche principale : `main`.

## Accès local

L'application écoute le **port 8040** (`server.port`, surchargeable par `DQ_PORT`), des deux côtés
de la publication compose : http://localhost:8040/dq/. 8080 était déjà pris sur le poste.
En production, le nginx de l'hôte relaie toujours vers `127.0.0.1:8081`, côté conteneur 8040.

## Déploiement

DQ-Analyzer est servi sous `/dq/` derrière le nginx d'ECHO (SEC-03).
Voir `docs/GUIDE_DEPLOIEMENT.md` et `docs/GUIDE_PROBLEMES_DEPLOIEMENT.md`.

## Décisions et pièges à ne pas réapprendre

- Un nouveau type de règle = une classe `@Component` implémentant `RuleEvaluator` dans
  `rules/evaluators/`. On ne touche jamais au moteur pour en ajouter un (MAI-01).
- Toute la logique de score vit dans `execution/domain/Scoring`, nulle part ailleurs.
- Aucune valeur utilisateur concaténée en SQL : valeurs → `?`, noms de colonnes →
  `DuckDbIdentifiers.quote()` après vérification de leur existence dans la table importée.
- Jamais de chargement intégral d'un fichier en mémoire : lecture en flux + appender DuckDB.
- Schéma PostgreSQL : nouvelle migration Flyway `V<n>__description.sql`, jamais de
  modification d'une migration déjà commitée.
- Identifiants en anglais, textes visibles en français dans `messages.properties`.
- Les numéros de migration du guide des lots sont décalés : le guide appelait V2 la migration
  « propriétaire métier », elle est partie en **V5** (V2 à V4 étaient déjà prises par l'import).
  Toujours vérifier `src/main/resources/db/migration` avant de nommer une migration.
- `param` est un mot réservé de Thymeleaf : ne jamais nommer ainsi une variable de `th:each`.
- Un objet de formulaire lu par Thymeleaf doit être `public` avec des accesseurs `public`
  (`RuleFormRequest`), sinon l'évaluation SpringEL échoue à l'exécution.
- Jackson 3 refuse une valeur absente pour un type primitif (`int`, `boolean`) : dans un contrat
  d'échange JSON écrit à la main (`RuleExchange.RuleExport`), utiliser des types boxés.
- Les ITs partagent un seul conteneur PostgreSQL : garder la même combinaison d'annotations que
  les autres (`@Import(TestcontainersConfiguration.class) @SpringBootTest @AutoConfigureMockMvc
  @ActiveProfiles("test")`), sinon Spring crée un second contexte et un second conteneur, que le
  poste de dev ne démarre pas de façon fiable. Podman doit tourner : `podman machine start`.

## Journal de session

- 2026-10-28 — Création de ce fichier. Étapes 15 à 18 terminées.
- 2026-10-28 — Étape 19, incréments 1 à 3 sur `feat/regles-modeles` (3 commits, `verify` vert :
  223 tests unitaires, 66 d'intégration) : migration V5 (propriétaire métier, validation,
  séquence `dq_rule_code_seq`, `source_file.profile`), `RuleCatalogService` (versions),
  `RuleDefinitionReader`, `RuleCatalogQueries`, module `templates` avec `ColumnMatcher`,
  écran « Règles de qualité » filtrable + historique, formulaire généré depuis `paramSchema()`
  avec éditeur de conditions sans texte libre (`rule-form.js`) et vérification de la règle sur
  une table vide avant enregistrement. Les suggestions du profil ouvrent le formulaire prérempli.
  Suite prévue : REG-03, REG-05, MOD-01 à MOD-03.
- 2026-10-28 — Étape 19 terminée (6 commits au total, `verify` vert : 227 tests unitaires,
  84 d'intégration) : essai d'une règle sur un fichier chargé (`RuleTester`, 50 anomalies au plus,
  rien d'enregistré), `ImportService.loadedFiles`, export/import JSON (`RuleExchange` : réimport
  versionne au lieu de dupliquer, règle invalide isolée), modèles d'analyse (`TemplateService`,
  écrans liste / formulaire / association des colonnes) et `TemplateRules.remap` qui traduit les
  colonnes attendues en colonnes du fichier, y compris dans les conditions et les calculs.
  Port basculé sur 8040. Prochaine étape : 20 (tables de référence, module `reference`) — à faire
  avant que EXA-01 et INT-01 soient réellement utilisables (`ReferenceProvider.NONE` aujourd'hui).
