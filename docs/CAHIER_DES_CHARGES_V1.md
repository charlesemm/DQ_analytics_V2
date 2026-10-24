# Cahier des charges — DQ-Analyzer (V1)

Outil d'analyse de la qualité des données Master Data — Service Gouvernance des Données, DSI CNAM
22 oct. 2026 · Mathieu Charles N'GUESSAN

## 1. Contexte et objectifs

La V1 permet à un analyste Master Data de charger un fichier (Excel, CSV, XML, JSON, Parquet…) jusqu'à 2 Go, d'appliquer en quelques clics un jeu de règles de qualité défini à l'avance, et d'obtenir un rapport chiffré par dimension de qualité.

Constat actuel : les fichiers reçus (Excel le plus souvent) sont contrôlés à la main (doublons, champs vides, formats invalides, valeurs incohérentes) ; contrôles répétitifs, lents et variables selon la personne.

Objectifs V1 :
1. Industrialiser les contrôles récurrents (une règle écrite une fois, réutilisée).
2. Aligner les contrôles sur ISO/IEC 25012, ISO 8000, DAMA-DMBOK.
3. Produire un résultat mesurable : score par règle, dimension, colonne et global + lignes en anomalie.
4. Utilisable sans code (boutons et formulaires).
5. Tracer chaque analyse (qui, quand, quel fichier, quelles règles, quel résultat).

Nom de travail : **DQ-Analyzer** (à confirmer).

## 2. Référentiels et dimensions

| Référentiel | Apport |
|---|---|
| ISO/IEC 25012 | Vocabulaire des caractéristiques (exactitude, complétude, cohérence, crédibilité, actualité, conformité, précision, traçabilité) |
| ISO/IEC 25024 | Principe de mesure : ratio éléments conformes / éléments contrôlés |
| ISO 8000 | Exigences Master Data : syntaxe, conformité sémantique, provenance, processus qualité (8000-61) |
| DAMA-DMBOK | Dimensions opérationnelles : unicité, validité |

Dimensions V1 :

| Dimension | Définition | Corresp. ISO 25012 |
|---|---|---|
| Complétude | Valeur attendue présente (ni vide, ni bouche-trou) | Complétude |
| Unicité | Un objet métier n'apparaît qu'une fois selon sa clé | — (DAMA) |
| Validité | Format, type, longueur, liste autorisée respectés | Conformité, Précision |
| Exactitude | Valeur conforme à la réalité ou à une source de référence | Exactitude |
| Cohérence | Valeurs d'une ligne / lignes liées non contradictoires | Cohérence |
| Actualité | Donnée assez récente pour l'usage | Actualité |
| Intégrité référentielle | Valeur codée existant dans la table de référence | Cohérence, Conformité |

Hors périmètre des règles : disponibilité, portabilité, confidentialité, récupérabilité (dépendantes du système → exigences non fonctionnelles).

Limite assumée : l'exactitude est mesurée par comparaison à un fichier de référence chargé par l'analyste.

## 3. Périmètre V1

Inclus :
- Import de fichiers jusqu'à 2 Go : .xlsx/.xls, .ods, .csv/.tsv/.txt, largeur fixe, XML, JSON/JSONL, Parquet (feuille, ligne d'en-tête, séparateur, encodage).
- Profilage automatique des colonnes à l'import.
- Bibliothèque de règles paramétrables rangées par dimension.
- Modèles d'analyse (jeu de règles enregistré par type de fichier).
- Exécution en un clic, rapport à l'écran, export Excel / PDF.
- Tables de référence chargées par l'utilisateur.
- Historique des analyses, 3 profils utilisateurs, double authentification.

Exclus V1 : correction automatique des données, connexion directe Oracle/PostgreSQL, planification d'analyses, dédoublonnage par apprentissage automatique, workflow de validation multi-niveaux.

## 4. Acteurs et profils

| Profil | Droits |
|---|---|
| Administrateur | Utilisateurs, paramètres globaux, tous les objets |
| Analyste Data Quality | Règles, modèles, tables de référence ; lance les analyses ; exporte |
| Lecteur | Consulte rapports et historique ; exporte ; ne modifie rien |

## 5. Exigences fonctionnelles

Parcours type (6 étapes, chacune déclenchée par un bouton) : charger le fichier → vérifier le profil des colonnes → choisir un modèle ou cocher des règles → associer les colonnes → lancer l'analyse → lire le rapport, filtrer, exporter.

Priorité : M = obligatoire, S = souhaité.

### 5.1 Import des données

| ID | Exigence | P |
|---|---|---|
| IMP-01 | Importer un fichier (formats 5.1 bis) jusqu'à 2 Go, glisser-déposer ou Parcourir ; envoi par morceaux avec reprise après coupure réseau | M |
| IMP-02 | Choisir la feuille Excel et la ligne d'en-tête | M |
| IMP-03 | Fichier texte : choisir le séparateur (; , tabulation) et l'encodage (UTF-8, Windows-1252) | M |
| IMP-04 | Aperçu des 50 premières lignes avant validation | M |
| IMP-05 | Refuser un fichier vide, corrompu ou trop grand, avec message clair | M |
| IMP-06 | Conserver le fichier source, nom, taille, empreinte SHA-256 | M |
| IMP-07 | Détection automatique du format, de l'encodage et du séparateur, corrigeable | M |
| IMP-08 | Enregistrer les paramètres d'import (gabarit largeur fixe, chemin XML/JSON) dans le modèle | M |
| IMP-09 | Archives .zip contenant un seul fichier accepté | S |

#### 5.1 bis — Formats acceptés

Tous les formats sont ramenés à une forme tabulaire (lignes × colonnes) avant contrôle : les règles ne dépendent pas du format.

| Format | Extensions | Paramètres | Limite |
|---|---|---|---|
| Excel 2007+ | .xlsx, .xlsm | feuille, ligne d'en-tête | 1 048 576 lignes/feuille |
| Excel 97-2003 | .xls | feuille, ligne d'en-tête | 65 536 lignes ; lu en mémoire, 100 Mo max |
| OpenDocument | .ods | feuille, ligne d'en-tête | 100 Mo max |
| Texte délimité | .csv, .tsv, .txt | séparateur, encodage, guillemets, en-tête | 2 Go |
| Largeur fixe | .txt, .dat | position et longueur de chaque colonne (gabarit réutilisable) | 2 Go |
| XML | .xml | élément répété = une ligne (XPath simple) ; sous-éléments et attributs = colonnes | sous-niveaux répétés aplatis |
| JSON | .json, .jsonl, .ndjson | chemin du tableau d'objets, ou 1 ligne = 1 objet | objets imbriqués aplatis (adresse.ville) |
| Parquet | .parquet | aucun | 2 Go |

### 5.2 Profilage automatique

| ID | Exigence | P |
|---|---|---|
| PRF-01 | Par colonne : type détecté, nb de valeurs, de vides, de distinctes | M |
| PRF-02 | Par colonne : min, max, longueur min/max, 10 valeurs les plus fréquentes | M |
| PRF-03 | Par colonne : motifs de format dominants (99/99/9999, AAA-999) | S |
| PRF-04 | Proposer des règles à partir du profil (colonne 100 % distincte → unicité) | S |

### 5.3 Gestion des règles

| ID | Exigence | P |
|---|---|---|
| REG-01 | Créer une règle par formulaire : nom, description, dimension, type, colonne(s), paramètres, sévérité, seuil | M |
| REG-02 | Type choisi dans le catalogue §6 ; formulaire limité aux paramètres utiles | M |
| REG-03 | Tester une règle sur l'aperçu avant enregistrement | M |
| REG-04 | Activer, désactiver, dupliquer, modifier ; chaque modification crée une version | M |
| REG-05 | Export / import de règles en JSON | S |
| REG-06 | Recherche et filtre par dimension, modèle, auteur | M |

### 5.4 Modèles d'analyse

| ID | Exigence | P |
|---|---|---|
| MOD-01 | Modèle = nom, description, colonnes attendues, ensemble de règles | M |
| MOD-02 | Association automatique des colonnes (nom identique, insensible casse/accents) + correction manuelle | M |
| MOD-03 | Mémoriser les correspondances pour les fichiers suivants | S |

### 5.5 Tables de référence

| ID | Exigence | P |
|---|---|---|
| REF-01 | Charger une table de référence depuis Excel/CSV | M |
| REF-02 | Versionner et dater chaque version | M |
| REF-03 | Utiliser une table de référence dans les règles d'intégrité et d'exactitude | M |

### 5.6 Exécution et rapport

| ID | Exigence | P |
|---|---|---|
| EXE-01 | Lancer l'analyse par un bouton ; barre de progression | M |
| EXE-02 | Exécution en tâche de fond | M |
| RAP-01 | Tableau de bord : score global, par dimension, par règle, par colonne | M |
| RAP-02 | Par règle : lignes contrôlées, conformes, en anomalie, taux, seuil, statut (OK/Alerte/KO) | M |
| RAP-03 | Anomalies ligne par ligne : n° ligne, colonne, valeur, règle, message ; filtres et tri | M |
| RAP-04 | Unicité : regrouper les doublons par groupe | M |
| RAP-05 | Export Excel : onglet Synthèse, onglet Anomalies, fichier d'origine + colonne « Anomalies » et surlignage | M |
| RAP-06 | Export synthèse PDF 1-2 pages | S |

### 5.7 Historique et suivi

| ID | Exigence | P |
|---|---|---|
| HIS-01 | Lister les analyses : date, utilisateur, fichier, modèle, score global | M |
| HIS-02 | Rouvrir le rapport d'une analyse passée | M |
| HIS-03 | Comparer deux analyses d'un même modèle | S |

### 5.8 Utilisateurs

| ID | Exigence | P |
|---|---|---|
| USR-01 | Authentification identifiant / mot de passe | M |
| USR-02 | Rôles Administrateur, Analyste, Lecteur | M |
| USR-03 | Authentification LDAP / Active Directory CNAM | S |

Double authentification, obligatoire pour tous les comptes :

| ID | Exigence | P |
|---|---|---|
| MFA-01 | TOTP (RFC 6238), compatible Google et Microsoft Authenticator | M |
| MFA-02 | Enrôlement à la première connexion par QR code + saisie d'un premier code | M |
| MFA-03 | Alternative : code à 6 chiffres par e-mail, valable 5 minutes | M |
| MFA-04 | Choix de méthode, changement après nouvelle vérification | M |
| MFA-05 | 10 codes de secours à usage unique, stockés hachés | M |
| MFA-06 | Blocage temporaire après 5 codes erronés ; réinitialisation par un administrateur | M |
| MFA-07 | Chaque événement 2FA tracé dans le journal d'audit | M |

## 6. Catalogue des 23 types de règles V1

Chaque règle porte : sévérité (Bloquante, Majeure, Mineure), seuil d'acceptation en %, message personnalisable.

| Code | Dimension | Type de règle | Paramètres | Exemple CNAM |
|---|---|---|---|---|
| CMP-01 | Complétude | Champ obligatoire non vide | colonne | Le n° de sécurité sociale est renseigné |
| CMP-02 | Complétude | Valeur bouche-trou interdite | colonne, liste (N/A, 0, XXX, 01/01/1900) | Pas de date de naissance au 01/01/1900 |
| CMP-03 | Complétude | Obligatoire sous condition | colonne, condition | Si statut = Ayant droit, n° assuré principal renseigné |
| CMP-04 | Complétude | Taux de remplissage minimal | colonne, % | Téléphone renseigné sur au moins 80 % des lignes |
| UNI-01 | Unicité | Valeur unique sur une colonne | colonne | Un n° d'immatriculation n'apparaît qu'une fois |
| UNI-02 | Unicité | Clé composée unique | liste de colonnes | Nom + prénoms + date de naissance uniques |
| UNI-03 | Unicité | Doublons après normalisation | colonnes, options (casse, accents, espaces) | « KOUAMÉ Jean » = « kouame jean » |
| UNI-04 | Unicité | Doublons approchés | colonnes, algorithme (Levenshtein, Jaro-Winkler), seuil | « N'GUESSAN » / « NGUESSAN » |
| VAL-01 | Validité | Type de donnée | colonne, type (entier, décimal, date, texte, booléen) | Le montant est un nombre |
| VAL-02 | Validité | Format par expression régulière | colonne, regex ou motif prédéfini | Téléphone ivoirien sur 10 chiffres |
| VAL-03 | Validité | Longueur | colonne, min, max | Longueur attendue du n° CNPS |
| VAL-04 | Validité | Plage de valeurs | colonne, min, max | Âge entre 0 et 120 ans |
| VAL-05 | Validité | Liste de valeurs autorisées | colonne, liste | Sexe ∈ {M, F} |
| VAL-06 | Validité | Format de date | colonne, formats acceptés | jj/mm/aaaa uniquement |
| VAL-07 | Validité | Caractères interdits | colonne, caractères | Pas de chiffres dans un nom |
| EXA-01 | Exactitude | Concordance avec un fichier de référence | colonne clé, colonne comparée, table de référence | Date de naissance = celle de la base des assurés |
| EXA-02 | Exactitude | Clé de contrôle (checksum) | colonne, algorithme (modulo 97, Luhn…) | Validation d'un identifiant à clé |
| COH-01 | Cohérence | Comparaison entre deux colonnes | colonne A, opérateur, colonne B | Date de début ≤ date de fin |
| COH-02 | Cohérence | Règle conditionnelle SI… ALORS | condition, contrôle | Si sexe = M, pas de prestation maternité |
| COH-03 | Cohérence | Valeur calculée | colonne cible, expression | Total = quantité × prix unitaire |
| ACT-01 | Actualité | Âge maximal de la donnée | colonne date, délai (jours/mois) | Dernière mise à jour de moins de 12 mois |
| ACT-02 | Actualité | Date non future | colonne date | Pas de date de naissance dans le futur |
| INT-01 | Intégrité référentielle | Existence dans une table de référence | colonne, table, colonne de la table | Le code commune existe au référentiel des communes |

Motifs prédéfinis à livrer (VAL-02) : e-mail, téléphone Côte d'Ivoire (10 chiffres), date jj/mm/aaaa, code postal, alphanumérique majuscule. Formats des identifiants CNAM / CNPS à fournir par le métier.

Normalisation avant contrôle (option par règle) : suppression des espaces en début et fin, mise en majuscules, suppression des accents. La valeur affichée dans les anomalies reste la valeur d'origine.

## 7. Calcul des indicateurs et scoring

- Score d'une règle = lignes conformes / lignes contrôlées × 100 (lignes contrôlées = lignes concernées par la règle, hors lignes exclues par une condition).
- Score d'une dimension = moyenne des scores de ses règles pondérée par la sévérité (Bloquante 3, Majeure 2, Mineure 1 ; modifiable par l'administrateur) : S_dim = Σ wi·Si / Σ wi.
- Score global = moyenne des dimensions contrôlées, poids par dimension configurable dans le modèle (égal par défaut). Une dimension sans règle n'entre pas dans le calcul et s'affiche « Non mesurée ».
- Score par colonne = même calcul, restreint aux règles portant sur la colonne.

Statut d'une règle :

| Statut | Condition |
|---|---|
| OK | Score ≥ seuil d'acceptation de la règle |
| Alerte | Score < seuil, règle Mineure ou Majeure |
| KO | Score < seuil, règle Bloquante |

Statut global de l'analyse : KO dès qu'une règle Bloquante est KO, sinon Alerte s'il existe au moins une Alerte, sinon OK. Un bon score global avec un statut KO est voulu : une anomalie bloquante ne doit pas être masquée par la moyenne.

Unicité : une ligne est en anomalie si elle appartient à un groupe de doublons ; le rapport indique le nombre de groupes et de lignes en surplus (lignes du groupe moins une).

Indicateurs de profil affichés à côté des scores : nombre de lignes, de colonnes, taux de remplissage global, nombre total d'anomalies, répartition des anomalies par sévérité.

## 8. Exigences non fonctionnelles

| ID | Domaine | Exigence |
|---|---|---|
| PERF-01 | Performance | 100 000 lignes × 30 colonnes avec 20 règles en moins de 60 s ; CSV de 2 Go en moins de 15 min (hors doublons approchés) |
| PERF-02 | Performance | Fichiers jusqu'à 2 Go (seuil paramétrable), envoyés par morceaux de 10 Mo avec reprise |
| PERF-03 | Performance | Jamais de chargement intégral en mémoire : lecture en flux puis base analytique embarquée sur disque (DuckDB) ; disque serveur au moins 3 × la taille max par analyse simultanée |
| SEC-01 | Sécurité | Mots de passe hachés BCrypt, 2FA obligatoire (§5.8), sessions expirant après 30 min d'inactivité |
| SEC-02 | Sécurité | Contrôle d'accès par rôle sur chaque écran et chaque API |
| SEC-03 | Sécurité | HTTPS obligatoire, protection CSRF, validation des fichiers à l'import (extension, type MIME réel, taille) |
| SEC-04 | Sécurité | Purge des fichiers sources et des anomalies après une durée paramétrable (90 j par défaut) ; les scores restent |
| SEC-05 | Conformité | Loi ivoirienne n° 2013-450 sur la protection des données personnelles et PSSI de la CNAM |
| TRA-01 | Traçabilité | Journal d'audit : connexions, imports, créations et modifications de règles, lancements d'analyse, exports |
| TRA-02 | Traçabilité | Chaque résultat référence la version exacte des règles et des tables de référence (reproductibilité) |
| ERG-01 | Ergonomie | Interface en français ; toute action courante accessible en 3 clics au plus depuis l'accueil |
| ERG-02 | Ergonomie | Messages d'anomalie compréhensibles par un non-informaticien |
| MAI-01 | Maintenabilité | Ajouter un nouveau type de règle = ajouter une classe Java, sans modifier le moteur (§9) |
| MAI-02 | Maintenabilité | Couverture de tests unitaires ≥ 70 % sur le moteur de règles |
| EXP-01 | Exploitation | Déploiement par JAR exécutable ou conteneur Docker ; configuration externalisée |
| EXP-02 | Exploitation | Logs applicatifs fichiers avec rotation ; point de santé (Spring Boot Actuator) |

## 9. Architecture technique

Application Spring Boot monolithique et modulaire : simple à déployer sur un serveur interne, découpable plus tard. Les règles et modèles alimentent le moteur, qui produit les résultats repris par les rapports ; tous les modules passent par la même couche d'accès aux données.

### 9.2 Stack technique

| Couche | Choix V1 | Justification |
|---|---|---|
| Langage | Java 21 (LTS) | Support long, records, pattern matching |
| Framework | Spring Boot 4.1 | Web, sécurité, accès aux données, tâches asynchrones |
| Interface | Thymeleaf + Bootstrap 5 + HTMX | Une seule application à déployer, pas de front séparé en V1 |
| Graphiques | Chart.js | Scores par dimension et évolution |
| Lecture des fichiers | Apache POI streaming, Commons CSV, StAX (XML), Jackson streaming (JSON), Apache Tika (type réel) | Standards Java, gros fichiers |
| Export | Apache POI (Excel), OpenPDF (PDF) | Libres, sans licence commerciale |
| Similarité de texte | Apache Commons Text | Levenshtein, Jaro-Winkler pour UNI-04 |
| Base de l'outil | PostgreSQL | Cohérent avec la migration Oracle → PostgreSQL de la DSI |
| Migrations de schéma | Flyway | Schéma versionné |
| Sécurité | Spring Security | Rôles, BCrypt, LDAP en option |
| Tests | JUnit 5, Mockito, Testcontainers | Moteur et base |
| Build / déploiement | Maven, podman, CI/CD | JAR ou conteneur |
| Moteur d'analyse | DuckDB embarqué (pilote JDBC) | Base colonnes sur disque : 2 Go sans saturer la mémoire, CSV et Parquet natifs, Levenshtein et Jaro-Winkler en SQL |
| Double authentification | dev.samstevens.totp, ZXing (QR code), Spring Mail | TOTP standard (Google, Microsoft Authenticator) ; codes e-mail par SMTP interne |

### 9.3 Conception du moteur de règles

Chaque type de règle est une classe qui implémente une interface commune ; le moteur les découvre automatiquement (patron Stratégie + injection Spring). Les paramètres d'une règle sont stockés en JSON : ajouter un type ne change pas la base.

```java
public interface RuleEvaluator {
    String type();                         // ex. "UNI-02"
    Dimension dimension();                 // ex. UNICITE
    ParamSchema paramSchema();             // paramètres attendus, pour générer le formulaire
    RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs);
}
```

Les règles ligne à ligne (complétude, validité, cohérence) s'exécutent en un seul passage ; les règles d'ensemble (unicité, taux de remplissage) font un passage dédié.

Exécution sur gros volumes : le fichier importé est chargé une fois dans une table DuckDB (avec le numéro de ligne d'origine). Chaque type de règle traduit ses paramètres en requête SQL qui compte les lignes conformes et extrait les anomalies. Les expressions saisies par l'utilisateur (conditions, valeurs calculées) passent par un petit langage contrôlé, jamais par du SQL libre, pour éviter toute injection.

### 9.4 Modèle de données (principales tables)

| Table | Rôle | Champs clés |
|---|---|---|
| app_user | Utilisateurs | login, nom, mot_de_passe_hash, rôle, actif |
| dq_rule | Règle de qualité | code, nom, dimension, type, colonnes_cibles, paramètres (JSON), sévérité, seuil, message, version, actif |
| analysis_template | Modèle d'analyse | nom, description, colonnes_attendues (JSON), poids_dimensions (JSON) |
| template_rule | Lien modèle ↔ règle | template_id, rule_id, ordre |
| reference_table | Table de référence | nom, version, date_chargement, colonnes |
| reference_value | Valeurs de référence | reference_table_id, valeurs (JSON) |
| source_file | Fichier importé | nom, taille, sha256, nb_lignes, nb_colonnes, chemin_stockage |
| analysis_run | Exécution d'analyse | fichier, modèle, utilisateur, début, fin, statut, score_global |
| rule_result | Résultat par règle | run_id, rule_id, version_règle, contrôlées, conformes, score, statut |
| anomaly | Anomalie ligne à ligne | run_id, rule_id, n° ligne, colonne, valeur, message, groupe_doublon |
| audit_log | Journal d'audit | date, utilisateur, action, objet, détail |

### 9.5 Principales API REST

| Méthode | Chemin | Usage |
|---|---|---|
| POST | /api/files | Importer un fichier |
| GET | /api/files/{id}/profile | Profil des colonnes |
| GET / POST / PUT | /api/rules | Lister, créer, modifier une règle |
| POST | /api/rules/{id}/test | Tester une règle sur un fichier |
| GET / POST | /api/templates | Modèles d'analyse |
| POST | /api/runs | Lancer une analyse |
| GET | /api/runs/{id} | Statut et scores |
| GET | /api/runs/{id}/anomalies | Anomalies paginées et filtrables |
| GET | /api/runs/{id}/export?format=xlsx\|pdf | Export du rapport |

## 10. Livrables, planning et recette

11 phases, durée indicative de 16 semaines à confirmer selon la disponibilité de l'équipe (micro-étapes suivies dans l'onglet Plan de réalisation).

Livrables :
- Code source sur dépôt Git, avec README d'installation.
- Application déployable (JAR + Dockerfile) et scripts de base de données Flyway.
- Jeu de règles initial pour au moins 2 types de fichiers réels du service.
- Manuel utilisateur (créer une règle, un modèle, lancer une analyse, lire un rapport).
- Guide développeur : ajouter un nouveau type de règle.
- Cahier de recette et procès-verbal de recette.

Critères d'acceptation :
- [ ] Les 23 types de règles fonctionnent sur un fichier de test à anomalies connues, et chaque anomalie attendue est détectée.
- [ ] Les scores calculés correspondent au calcul manuel sur le fichier de test.
- [ ] Une analyse complète se fait sans écrire de code, en moins de 5 minutes pour un fichier déjà associé à un modèle.
- [ ] L'export Excel ouvre sans erreur et surligne les cellules en anomalie.
- [ ] L'objectif PERF-01 est atteint.
- [ ] Un Lecteur ne peut ni créer ni modifier une règle (test d'autorisation).
- [ ] Une analyse passée se rouvre avec la version des règles utilisée à l'époque.
- [ ] Un CSV de 2 Go est importé après une coupure réseau simulée (reprise) et analysé dans le délai.
- [ ] Chaque format du tableau 5.1 bis est importé sur un fichier d'exemple.
- [ ] La connexion exige le second facteur, par Google Authenticator, Microsoft Authenticator et par e-mail.

Pistes V2 : connexion directe aux bases Oracle et PostgreSQL ; planification des analyses et alertes e-mail ; proposition de corrections et export d'un fichier corrigé ; workflow de traitement des anomalies ; tableau de bord de suivi de la qualité dans le temps ; lien avec un glossaire métier et un dictionnaire de données.

Questions ouvertes :
- Quels formats exacts pour les identifiants CNAM et CNPS (motifs prédéfinis) ?
- Sur quel serveur l'outil sera-t-il hébergé, et qui l'exploite ?
- L'authentification par l'annuaire CNAM est-elle attendue dès la V1 ?
- Quels sont les 2 premiers types de fichiers à modéliser pour la recette ?
- Un serveur SMTP interne est-il disponible pour l'envoi des codes par e-mail ?
- Quel espace disque et quelle mémoire sur le serveur cible (fichiers de 2 Go) ?
