# DQ-Analyzer

Analyse de la qualité des données Master Data — DSI CNAM-CI.

Application Spring Boot (Java 21, Thymeleaf + htmx) qui importe des fichiers de
données, les analyse via DuckDB au regard de règles de qualité paramétrables, et
restitue scores et anomalies.

- **Base applicative** : PostgreSQL 17 (schéma géré par Flyway)
- **Moteur d'analyse** : DuckDB, sur fichiers déposés dans `dq.storage.path`
- **Authentification** : identifiant / mot de passe + code 2FA envoyé par e-mail

---

## 1. Prérequis

| Outil | Version | Remarque |
|---|---|---|
| JDK | 21 | requis par le build (`java.version`) |
| Podman (ou Docker) | récent | `podman compose` / `docker compose` |
| Maven | — | inutile, le wrapper `./mvnw` est fourni |

Aucune installation de PostgreSQL n'est nécessaire : la base tourne en conteneur.

---

## 2. Lancement rapide — tout en conteneurs

Depuis la racine du dépôt :

```bash
podman compose up -d --build
```

Trois services démarrent :

| Service | Rôle | Adresse |
|---|---|---|
| `dq-app` | l'application | <http://localhost:8040/dq/> |
| `dq-postgres` | base PostgreSQL | `127.0.0.1:5435` |
| `dq-mailpit` | faux serveur SMTP | <http://localhost:8025> |

> ⚠️ L'application est servie sous le sous-chemin **`/dq/`** (et non à la racine) :
> l'URL `http://localhost:8040/` renvoie une 404, c'est normal.

Suivre le démarrage :

```bash
podman compose logs -f app
```

Arrêter :

```bash
podman compose down          # garde les données
podman compose down -v       # supprime aussi la base et le stockage
```

### Comptes de démonstration (profil `dev` uniquement)

Chargés automatiquement par Flyway. Mot de passe commun : **`Admin2026!`**

| Identifiant | Rôle |
|---|---|
| `admin` | ADMIN |
| `analyste` | ANALYST |
| `lecteur` | READER |

Le code 2FA envoyé à la connexion est à lire dans **MailPit** : <http://localhost:8025>.

---

## 3. Lancement depuis l'éditeur (IntelliJ) ou en ligne de commande

Pratique pour le développement : seules les dépendances tournent en conteneur,
l'application tourne sur le poste (rechargement plus rapide, débogueur).

```bash
# 1. démarrer uniquement la base et le SMTP de test
podman compose up -d postgres mailpit

# 2. lancer l'application (profil dev par défaut)
./mvnw spring-boot:run
```

Puis <http://localhost:8040/dq/>.

Le profil `dev` pointe alors sur `localhost:5435` (port exposé par le conteneur
PostgreSQL ; `5432` est laissé libre pour un éventuel service PostgreSQL local).

Depuis IntelliJ : lancer la classe `DqAnalyzerApplication`, aucune variable
d'environnement à ajouter — `spring.profiles.default` vaut déjà `dev`.

---

## 4. Build, tests, formatage

```bash
./mvnw verify          # compile, tests, contrôle de formatage, couverture JaCoCo
./mvnw test            # tests seuls
./mvnw spotless:apply  # reformate le code (à faire avant de committer)
```

Le rapport de couverture est produit dans `target/site/jacoco/index.html`.

---

## 5. Configuration

La configuration commune est dans `src/main/resources/application.yml`, les
valeurs propres à un environnement dans `application-dev.yml` et
`application-prod.yml`.

Principaux réglages surchargeables par variable d'environnement :

| Variable | Défaut | Rôle |
|---|---|---|
| `DQ_PORT` | `8040` | port d'écoute de Tomcat |
| `DQ_CONTEXT_PATH` | `/dq` | sous-chemin de service |
| `DQ_STORAGE_PATH` | `./storage` | fichiers importés et bases DuckDB |

Prévoir sur le disque **3 × la taille maximale d'un fichier analysé** (PERF-03).
Taille d'upload maximale : 2 Go, par morceaux de 10 Mo.

---

## 6. Déploiement en production

Voir le guide détaillé : [`docs/GUIDE_DEPLOIEMENT.md`](docs/GUIDE_DEPLOIEMENT.md).

En résumé, sur le serveur :

```bash
cp .env.example .env     # puis renseigner les mots de passe, chmod 600 .env
podman compose -f compose.prod.yaml up -d --build
./deploy/creer-admin.sh  # en prod aucun compte n'est préchargé
```

L'application vit derrière le nginx de l'hôte, sous
`https://echo.ipscnam.ci/dq/` (voir `deploy/nginx/`).

> ⚠️ `DQ_CONTEXT_PATH` et le `location` du vhost nginx doivent toujours être
> modifiés **ensemble** : désaccordés, toutes les pages renvoient 404.

---

## 7. Documentation du projet

| Document | Contenu |
|---|---|
| [`docs/CAHIER_DES_CHARGES_V1.md`](docs/CAHIER_DES_CHARGES_V1.md) | besoins et exigences |
| [`docs/partie_1.md`](docs/partie_1.md) | conception |
| [`docs/Partie 2 — Réalisation des lots.md`](docs/Partie%202%20—%20Réalisation%20des%20lots.md) | réalisation, lot par lot |
| [`docs/GUIDE_DEPLOIEMENT.md`](docs/GUIDE_DEPLOIEMENT.md) | installation sur le serveur |
| [`docs/SCENARIO_RECETTE.md`](docs/SCENARIO_RECETTE.md) | scénarios de recette |
| [`HELP.md`](HELP.md) | références Spring Boot / Maven |

---

## 8. Dépannage

| Symptôme | Cause probable |
|---|---|
| 404 sur toutes les pages | URL sans `/dq/` à la fin |
| `Port 8040 already in use` | une instance tourne déjà (`podman compose ps`) |
| `Connection refused` vers la base | `postgres` pas encore sain — attendre le *healthcheck* |
| Pas de code 2FA reçu | le lire dans MailPit (<http://localhost:8025>), pas en boîte mail |
| Échec Flyway `validate` | base d'une version antérieure : `podman compose down -v` puis relancer |