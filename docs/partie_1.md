# DQ-Analyzer — Guide de mise en place du projet

Oct 23, 2026 · @Mathieu Charles N'GUESSAN

À la fin de ce guide, tu as un projet Spring Boot 4.1 qui démarre, une page de connexion avec 3 comptes de test (Administrateur, Analyste, Lecteur), le schéma PostgreSQL complet, le moteur de règles avec la première règle CMP-01 et le calcul des scores, et une commande `./mvnw verify` qui passe au vert. Compte environ 2 heures. Suis les étapes dans l'ordre : chaque étape se termine par une vérification, et chaque instruction est suivie d'une explication (ce qu'elle fait, pourquoi on la fait).

## Vue d'ensemble : comment les éléments interagissent

&#91;embedded content: architecture de DQ-Analyzer · modules, bases et flux\]

Comment lire la carte : le navigateur n'entre que par `security`, qui laisse passer la requête vers les pages. Une page lancera une analyse dans `execution`, qui appelle les évaluateurs de `rules` ; ceux-ci interrogent DuckDB, où `importing` aura chargé le fichier. PostgreSQL garde tout ce qui doit durer (comptes, règles, résultats, journal) ; son schéma est créé par Flyway au démarrage. Les cadres en pointillés sont les lots à venir. Les résultats et anomalies seront écrits dans PostgreSQL par `execution` à l'étape « exécution asynchrone » ; les codes 2FA partiront par e-mail (MailPit en dev).

## Prérequis

Installe ces outils avant de commencer. Maven n'est pas nécessaire : le projet embarque son propre Maven (`mvnw`).

| Outil | Version | Rôle | Vérification |
| --- | --- | --- | --- |
| JDK Eclipse Temurin | 21 (LTS) | Compiler et lancer l'application | `java -version` → doit afficher `21.x` |
| Git | 2.40 ou plus | Gestion de versions | `git --version` |
| Podman + podman compose | Podman 5.x | PostgreSQL et MailPit en local, tests d'intégration | `podman --version` puis `podman compose version` |
| IntelliJ IDEA (l'édition gratuite suffit) | récente | Éditeur | — |
| Claude Code | dernière | Développer avec Claude dans le projet | `claude --version` |

**Explication de chaque outil.**

- **JDK 21 Temurin** : le JDK contient le compilateur (`javac`) et la machine virtuelle (`java`). La version 21 est une LTS (support long, environ 8 ans de correctifs) : c'est la version qu'on choisit pour une application d'entreprise, et c'est le minimum requis par Spring Boot 4. Temurin est la distribution gratuite et sans licence commerciale de la fondation Eclipse ; celle d'Oracle a des conditions d'usage plus contraignantes en entreprise.
- **Git** : garde l'historique de chaque modification du code. Il permet de revenir en arrière, de travailler sur une branche sans casser la version stable, et c'est un livrable exigé par le CDC (§10).
- **Podman** : lance des conteneurs, c'est-à-dire des logiciels déjà installés et isolés (ici PostgreSQL). Tu n'installes donc pas PostgreSQL sur ton poste : un `podman compose up` le démarre, un `down` l'arrête, et tout le monde a exactement la même version. Podman fait la même chose que Docker, mais sans service tournant en administrateur et sans licence payante ; c'est l'outil prévu par le CDC.
- **podman compose** : lit un fichier `compose.yaml` qui décrit plusieurs conteneurs (PostgreSQL + MailPit) et les démarre ensemble, au lieu de taper une longue commande par conteneur.
- **IntelliJ IDEA** : l'éditeur complète le code, signale les erreurs avant compilation, lance l'application et les tests d'un clic. L'édition gratuite suffit pour tout ce guide ; l'abonnement Ultimate ajoute l'aide spécifique à Spring et l'outil Base de données, pratiques mais pas indispensables. Les réglages propres à ce projet sont dans l'étape 2 bis.
- **Claude Code** : l'assistant qui travaille directement dans ton dossier ; il lit le `CLAUDE.md` à chaque session.

**Pourquoi pas Maven** : le projet contient un script `mvnw` (« Maven Wrapper ») qui télécharge la bonne version de Maven au premier lancement. Tout le monde, y compris le futur serveur d'intégration continue, construit donc avec exactement la même version.

**Pourquoi JAVA\_HOME** : c'est la variable que Maven et les outils lisent pour trouver le JDK. Si elle pointe vers un ancien Java (8, 11, 17), la compilation échoue même si `java -version` affiche 21.

**Pourquoi la machine Podman sous Windows** : les conteneurs ont besoin d'un noyau Linux. Sous Windows et macOS, Podman crée une petite machine virtuelle Linux (`podman machine init`) qu'il faut démarrer après chaque redémarrage du PC (`podman machine start`).

Points d'attention :

- **JAVA\_HOME** doit pointer vers le JDK 21. Sous Windows (PowerShell) : `echo $env:JAVA_HOME` ; sous Linux/macOS : `echo $JAVA_HOME`.
- **Podman sous Windows ou macOS** tourne dans une machine virtuelle. À faire une seule fois : `podman machine init`, puis à chaque redémarrage du poste : `podman machine start`.
- **podman compose** appelle un fournisseur externe. S'il répond qu'aucun fournisseur n'est trouvé, installe-le : `pip install podman-compose`.
- **Réseau CNAM** : si un proxy filtre Internet, Maven doit pouvoir joindre `repo.maven.apache.org` et Podman `docker.io`. Sinon, demande à l'équipe réseau l'adresse du proxy ou du miroir interne (voir Dépannage).

Vérification de fin d'étape : les 4 commandes du tableau répondent sans erreur et `java -version` affiche bien 21.

## Étape 1 — Créer le dossier et le dépôt Git

On ne crée ici que le dossier de travail et l'identité Git ; le dépôt lui-même sera initialisé à l'étape 2, une fois le squelette décompressé.

1. Choisis un dossier sans espace ni accent dans le chemin (Maven et Podman le supportent mal). Exemple : `C:\dev` sous Windows, `~/dev` sous Linux.
2. Configure ton identité Git si ce n'est pas déjà fait :

```bash
git config --global user.name "Mathieu Charles N'GUESSAN"
git config --global user.email "ton.adresse@exemple.ci"
git config --global init.defaultBranch main
git config --global core.autocrlf input   # Windows : garde les fins de ligne LF dans le dépôt
```

**Explication.**

- **Chemin sans espace ni accent** : certains scripts (dont `mvnw.cmd`) et Podman sous Windows coupent les chemins au premier espace ou lisent mal les accents. `C:\Users\Mathieu\Mes Documents\Projets` provoque des erreurs difficiles à comprendre ; `C:\dev` n'en provoque aucune.
- **`user.name` et `user.email`** : chaque commit enregistre son auteur. Sans ces deux valeurs, Git refuse de commiter ou invente une identité à partir du nom de la machine. Utilise l'adresse que tu utiliseras sur la forge (GitLab, GitHub), pour que tes commits te soient rattachés.
- **`--global`** : la valeur s'applique à tous tes dépôts sur ce poste, tu ne la saisis qu'une fois.
- **`init.defaultBranch main`** : nomme `main` la branche principale de tout nouveau dépôt. C'est la convention actuelle des forges ; sans ce réglage, certaines versions de Git créent `master`, et le premier envoi crée alors deux branches principales différentes.
- **`core.autocrlf input`** : Windows termine les lignes par deux caractères (CRLF), Linux par un seul (LF). Avec ce réglage, Git enregistre toujours LF dans le dépôt. Sans lui, un fichier peut sembler entièrement modifié alors qu'on n'a touché qu'une ligne, et le script `mvnw` ne s'exécute plus sous Linux (le serveur cible).

3. Ouvre un terminal dans ce dossier (`cd C:\dev` ou `cd ~/dev`). C'est là que tu décompresseras le projet à l'étape suivante.

Vérification : `git config --global --list` affiche ton nom, ton e-mail et `init.defaultbranch=main`.

## Étape 2 — Générer le squelette sur start.spring.io

Le générateur officiel de Spring produit un projet aux normes actuelles : `pom.xml` cohérent, Maven Wrapper (`mvnw`), classe principale et configuration Testcontainers. On le complète ensuite.

Ouvre [start.spring.io](https://start.spring.io) et saisis exactement ces valeurs :

| Champ | Valeur |
| --- | --- |
| Project | Maven |
| Language | Java |
| Spring Boot | la dernière **4.1.x** sans suffixe (ni SNAPSHOT, ni M, ni RC) |
| Group | `ci.cnam` |
| Artifact | `dq-analyzer` |
| Name | `dq-analyzer` |
| Description | `Analyse de la qualité des données Master Data — DSI CNAM` |
| Package name | `ci.cnam.dqanalyzer` (corrige-le s'il propose `ci.cnam.dq-analyzer`) |
| Packaging | Jar |
| Configuration | YAML |
| Java | 21 |

**Explication de chaque champ.**

- **Maven** : l'outil qui télécharge les bibliothèques, compile, lance les tests et fabrique le JAR. Gradle fait la même chose ; Maven est plus répandu dans les DSI et plus simple à lire.
- **Spring Boot 4.1.x sans suffixe** : SNAPSHOT, M (milestone) et RC (release candidate) sont des versions de test qui peuvent changer ou casser. Une version sans suffixe est stable et reçoit les correctifs de sécurité.
- **Group `ci.cnam`** : l'identifiant de l'organisation, écrit à l'envers comme un nom de domaine (`cnam.ci` → `ci.cnam`). Il évite qu'un autre projet du monde porte le même identifiant.
- **Artifact `dq-analyzer`** : le nom du projet ; il donne le nom du JAR produit (`dq-analyzer-0.1.0-SNAPSHOT.jar`).
- **Package name `ci.cnam.dqanalyzer`** : le dossier racine du code Java. Un tiret est interdit dans un nom de package Java, d'où `dqanalyzer` collé.
- **Jar** : l'application est livrée en un seul fichier exécutable qui contient son propre serveur web (Tomcat). On la lance avec `java -jar`, sans installer de serveur d'applications. C'est ce que demande EXP-01.
- **YAML** : la configuration s'écrit en arborescence indentée, plus lisible que le format `.properties` dès qu'il y a beaucoup de réglages.
- **Java 21** : doit correspondre au JDK installé.

Clique sur **ADD DEPENDENCIES** et ajoute ces 12 dépendances (tape le nom dans la recherche) :

| Nom dans start.spring.io | Pourquoi |
| --- | --- |
| Spring Web | Contrôleurs web et API REST |
| Thymeleaf | Pages HTML côté serveur |
| Validation | Validation des formulaires |
| Spring Security | Connexion, rôles, BCrypt |
| Spring Data JPA | Accès à PostgreSQL |
| Flyway Migration | Schéma versionné |
| PostgreSQL Driver | Pilote JDBC |
| Java Mail Sender | Codes 2FA par e-mail |
| Spring Boot Actuator | Point de santé (EXP-02) |
| Spring Modulith | Vérification des frontières entre modules |
| Spring Boot DevTools | Redémarrage automatique en développement |
| Testcontainers | PostgreSQL jetable pour les tests d'intégration |

**Explication.** Chaque dépendance ajoutée ici est un « starter » : un paquet qui regroupe les bibliothèques nécessaires à une fonction et leur configuration automatique. Par exemple, Spring Data JPA apporte Hibernate, le pool de connexions et la gestion des transactions, déjà réglés pour fonctionner ensemble. Spring Boot choisit leurs versions à ta place : c'est pour ça qu'on ne leur met jamais de numéro de version dans le `pom.xml`.

N'ajoute pas **Docker Compose Support** : avec Podman, il est plus fiable de lancer les services soi-même.

**Pourquoi pas Docker Compose Support** : ce module démarre et arrête lui-même les conteneurs à chaque lancement de l'application, en appelant la commande `docker`. Avec Podman, cet appel échoue souvent et l'application ne démarre plus. En lançant `podman compose up -d` toi-même, la base reste allumée entre deux lancements et le démarrage est plus rapide.

Ensuite :

1. Clique sur **GENERATE** : le navigateur télécharge `dq-analyzer.zip`.
2. Décompresse-le dans ton dossier de travail. Tu dois obtenir `C:\dev\dq-analyzer\pom.xml` (et non `dq-analyzer\dq-analyzer\pom.xml`).
3. Initialise le dépôt :

```bash
cd dq-analyzer
git init
```

**Explication.** `git init` crée le dossier caché `.git` qui contiendra tout l'historique du projet. On le fait maintenant, avant toute modification, pour pouvoir comparer plus tard ce que tu as changé par rapport au squelette généré (`git diff`).

4. Supprime le fichier de configuration généré, il sera remplacé à l'étape 6 : `src/main/resources/application.yaml` (ou `application.properties`).
5. Renomme le test généré `src/test/java/ci/cnam/dqanalyzer/DqAnalyzerApplicationTests.java` en `DqAnalyzerApplicationIT.java` : il démarre un conteneur, il doit donc tourner avec les tests d'intégration et non avec les tests unitaires. Son contenu sera remplacé à l'étape 10.

**Explication des points 4 et 5.**

- **Supprimer `application.yaml`** : Spring lit à la fois `application.yaml` et `application.yml`. Si les deux existent, les réglages se mélangent et on ne sait plus lequel s'applique. On garde un seul fichier, celui de l'étape 6.
- **Renommer en `…IT.java`** : Maven sépare deux familles de tests par leur nom. Les classes `*Test` / `*Tests` sont lancées par `./mvnw test` : elles doivent être rapides et ne rien démarrer d'extérieur. Les classes `*IT` (Integration Test) sont lancées seulement par `./mvnw verify`, car elles démarrent un conteneur PostgreSQL et prennent une à deux minutes. Ainsi, tu peux lancer les tests rapides des dizaines de fois par jour sans attendre.
- **Garder `TestcontainersConfiguration` et `TestDqAnalyzerApplication`** : le premier déclare le PostgreSQL jetable des tests d'intégration ; le second permet de lancer toute l'application avec ce PostgreSQL jetable (voir l'alternative de l'étape 11).

Garde tels quels les deux autres fichiers de test générés : `TestcontainersConfiguration.java` et `TestDqAnalyzerApplication.java`.

Vérification : à la racine, tu vois `pom.xml`, `mvnw`, `mvnw.cmd`, le dossier `.mvn` et le dossier `src`.

## Étape 2 bis — Ouvrir et régler le projet dans IntelliJ IDEA

À faire une fois, juste après avoir décompressé le projet (étape 2). Les menus sont ceux de Windows et Linux ; sur Mac, **File → Settings** s'appelle **IntelliJ IDEA → Settings**. Chaque réglage évite une erreur précise qu'on rencontre sinon plus tard.

### 2 bis.1 — Ouvrir le projet

1. **File → Open**, choisis le dossier `dq-analyzer` (celui qui contient `pom.xml`), puis **Trust Project**.
2. Attends la fin de la barre de progression en bas : IntelliJ lit le `pom.xml` et télécharge les bibliothèques. L'onglet **Maven** (à droite) doit afficher `dq-analyzer` sans erreur rouge.

**Explication.** On ouvre le dossier, on ne crée pas un « New Project » : le projet existe déjà, généré par start.spring.io. IntelliJ reconnaît le `pom.xml` et en déduit tout le reste (sources, tests, dépendances). À chaque modification du `pom.xml` (étape 3 et suivantes), une petite icône Maven apparaît en haut à droite de l'éditeur : clique dessus (ou **Ctrl+Maj+O**) pour recharger, sinon IntelliJ ne voit pas les nouvelles bibliothèques et souligne les imports en rouge alors que `./mvnw` compile.

### 2 bis.2 — Le JDK 21 et le Maven du projet

1. **File → Project Structure → Project** : **SDK** = le JDK 21 Temurin. S'il n'est pas dans la liste : **Add SDK → Download JDK**, version 21, fournisseur Eclipse Temurin. **Language level** : 21.
2. **File → Settings → Build, Execution, Deployment → Build Tools → Maven** : **Maven home path** = **Use Maven wrapper**. Dans le sous-menu **Runner**, **JRE** = **Use Project JDK**.

**Explication.** IntelliJ a son propre réglage de JDK, indépendant de `JAVA_HOME` : s'il garde un Java 17 installé ailleurs, tout est souligné en rouge (Spring Boot 4 exige 21). Choisir le Maven Wrapper garantit qu'IntelliJ et ton terminal construisent avec la même version de Maven que la CI.

### 2 bis.3 — L'encodage UTF-8, y compris pour les fichiers .properties

**File → Settings → Editor → File Encodings** : **Global Encoding**, **Project Encoding** et **Default encoding for properties files** sur **UTF-8**. Ne coche pas « Transparent native-to-ascii conversion ».

**Explication.** C'est le piège le plus courant : par défaut, IntelliJ enregistre les fichiers `.properties` en ISO-8859-1, alors que Spring lit `messages.properties` en UTF-8. Résultat : « Identifiant ou mot de passe incorrect » s'afficherait avec des `Ã©` à la place des accents. Fais ce réglage **avant** de créer `messages.properties` (étape 6).

Dans le même esprit, **File → Settings → Editor → Code Style → General → Line separator** : choisis **Unix and macOS (\\n)**. Sous Windows, IntelliJ termine sinon les lignes par CRLF ; le `.gitattributes` (étape 4) corrige les fichiers au moment du commit, mais pas ton script `db/init/01-create-database.sh`, qui est monté directement depuis ton dossier dans le conteneur PostgreSQL (étape 7). Avec des fins de ligne CRLF, ce script échoue sous Linux avec un message du type `bad interpreter` ou `set: illegal option`. Le séparateur du fichier ouvert s'affiche en bas à droite d'IntelliJ (`LF` ou `CRLF`) : un clic dessus permet de le changer.

### 2 bis.4 — Le formatage Palantir dans l'éditeur

1. **File → Settings → Plugins → Marketplace**, cherche **palantir-java-format**, installe-le et redémarre IntelliJ.
2. **File → Settings → Other Settings → palantir-java-format Settings** : coche **Enable palantir-java-format**.
3. **File → Settings → Tools → Actions on Save** : coche **Reformat code** et **Optimize imports**.

**Explication.** Spotless (étape 3) vérifie le format avec le style Palantir et fait échouer `./mvnw verify` au moindre écart. Avec le plugin, **Ctrl+Alt+L** (et chaque enregistrement) formate exactement comme Spotless : tu n'as plus de mauvaise surprise au moment du `verify`. Si le plugin n'existe pas pour ta version d'IntelliJ, garde simplement l'habitude de lancer `./mvnw spotless:apply` avant chaque commit.

### 2 bis.5 — Lancer l'application depuis IntelliJ

Pendant le développement, le plus rapide est de laisser PostgreSQL et MailPit dans Podman et de lancer l'application depuis IntelliJ (voir « Pour coder plus vite », étape 7).

1. Ouvre le terminal d'IntelliJ (**Alt+F12**, il s'ouvre à la racine du projet) : `podman compose up -d postgres mailpit`.
2. **Run → Edit Configurations → + → Application** :
   - **Name** : `DQ-Analyzer (dev)` ;
   - **Main class** : `ci.cnam.dqanalyzer.DqAnalyzerApplication` ;
   - **Environment variables** : `SPRING_PROFILES_ACTIVE=dev` ;
   - coche **Store as project file**.
3. Lance avec le triangle vert (**Maj+F10**). Après une modification du code : **Ctrl+F5** (relancer).

**Explication.**

- **`SPRING_PROFILES_ACTIVE=dev`** active `application-dev.yml` (base sur `localhost:5432`, comptes de démonstration, clés 2FA de développement). Sans lui, la connexion `admin` est refusée (voir Dépannage). La configuration « Application » existe dans toutes les éditions ; avec Ultimate, tu peux aussi utiliser la configuration « Spring Boot » et son champ **Active profiles**.
- **Store as project file** enregistre la configuration dans le dossier `.run/` du projet, qui n'est pas exclu par le `.gitignore` (seul `.idea/` l'est) : commite-le, ainsi la configuration est retrouvée telle quelle sur un autre poste.
- **Ne lance pas en même temps le conteneur `app`** (`podman compose up -d` sans préciser les services) : les deux voudraient le port 8080. Pour la vérification « comme en production » de l'étape 11, arrête d'abord l'application dans IntelliJ (carré rouge).

### 2 bis.6 — Les tests dans IntelliJ

Un triangle vert apparaît à côté de chaque classe et méthode de test : clic dessus, ou **Ctrl+Maj+F10** sur le test où se trouve le curseur.

Les tests d'intégration (Testcontainers) ont besoin de trouver Podman. Le terminal a peut-être déjà les variables du Dépannage, mais **IntelliJ ne les voit pas** s'il a été lancé depuis le menu du système. Règle-les une fois pour tous les tests :

- **Run → Edit Configurations → Edit configuration templates… → JUnit → Environment variables** :
  - Linux : `DOCKER_HOST=unix:///run/user/<ton uid>/podman/podman.sock;TESTCONTAINERS_RYUK_DISABLED=true` (ton uid s'affiche avec la commande `id -u`) ;
  - Windows : rien à ajouter si la compatibilité Docker est activée dans Podman Desktop (Dépannage).

**Explication.** Quand tu cliques sur un test, IntelliJ l'exécute lui-même, sans passer par Maven : c'est rapide, mais il ne lance ni Spotless, ni JaCoCo, ni la séparation tests unitaires / tests d'intégration. Pratique pendant que tu codes ; **avant chaque commit, lance quand même `./mvnw verify`** (terminal, ou onglet **Maven → Lifecycle → verify** en double-cliquant). C'est lui la référence, et c'est lui que lance la CI.

### 2 bis.7 — Voir les conteneurs et la base depuis IntelliJ (facultatif)

- **Conteneurs** : **File → Settings → Build, Execution, Deployment → Docker → +**, choisis la connexion **Podman** (ou le socket indiqué dans le Dépannage). L'onglet **Services** (**Alt+8**) liste alors `dq-postgres`, `dq-mailpit` et `dq-app`, avec leurs logs. Un clic droit sur `compose.yaml` permet aussi de démarrer les services.
- **Base PostgreSQL** (Ultimate) : onglet **Database → + → Data Source → PostgreSQL**, hôte `localhost`, port `5432`, base `dqanalyzer`, utilisateur `dqanalyzer`, mot de passe `dqanalyzer_dev`. Tu vois les tables créées par Flyway et tu peux tester les requêtes SQL du guide. Avec l'édition gratuite, le même résultat dans le terminal : `podman exec -it dq-postgres psql -U dqanalyzer dqanalyzer`.

**Explication.** Ce sont des confort, pas des obligations : les commandes `podman` du guide font la même chose. Ne te connecte jamais avec un outil de ce genre à un fichier `storage/files/*.duckdb` pendant qu'une analyse tourne : DuckDB n'accepte qu'un seul écrivain par fichier et l'analyse échouerait.

### 2 bis.8 — Claude Code dans IntelliJ

Ouvre le terminal d'IntelliJ à la racine du projet et tape `claude`. Installe aussi le plugin **Claude Code** depuis **Settings → Plugins → Marketplace** : Claude Code voit alors le fichier ouvert et ta sélection, et affiche ses modifications dans la vue de comparaison d'IntelliJ, où tu les acceptes ou les refuses. Si la liaison ne se fait pas toute seule, tape `/ide` dans Claude Code.

**Explication.** Travailler dans le terminal intégré garde tout au même endroit : Claude Code modifie les fichiers, IntelliJ les recharge aussitôt, et tu relis chaque diff avant de l'accepter, ce que demande le `CLAUDE.md`.

### 2 bis.9 — Raccourcis utiles (Windows et Linux)

| Raccourci | Action |
| --- | --- |
| Maj Maj | Chercher n'importe quoi (classe, fichier, réglage) |
| Alt+Entrée | Corriger l'erreur sous le curseur (import manquant, etc.) |
| Ctrl+B | Aller à la définition |
| Ctrl+Maj+T | Passer de la classe à son test, et inversement |
| Ctrl+Alt+L | Formater le fichier |
| Maj+F10 / Ctrl+F5 | Lancer / relancer l'application |
| Ctrl+Maj+F10 | Lancer le test sous le curseur |
| Alt+F12 | Terminal |

**Vérification de fin d'étape.** L'onglet Maven affiche le projet sans erreur, `Project Structure` indique le JDK 21, et l'encodage des fichiers .properties est UTF-8. Le lancement de l'application et des tests depuis IntelliJ se vérifiera à l'étape 11, une fois le code en place.

## Étape 3 — Compléter le pom.xml

On garde tout ce que start.spring.io a généré et on ajoute trois blocs : les versions des bibliothèques non gérées par Spring Boot, ces bibliothèques, et trois plugins (tests d'intégration, formatage, couverture).

Ouvre `pom.xml` et vérifie d'abord que la balise `<parent>` porte une version `4.1.x` et que `<java.version>` vaut `21`.

**Explication.** Le `pom.xml` est la fiche d'identité du projet pour Maven : nom, version de Java, bibliothèques, plugins. La balise `<parent>` hérite de `spring-boot-starter-parent`, qui fixe les versions de plusieurs centaines de bibliothèques testées ensemble par l'équipe Spring. C'est pourquoi les dépendances Spring n'ont pas de version : changer le numéro du parent suffit à tout mettre à jour d'un coup.

**3.1 — Dans `<properties>`**, ajoute sous `<java.version>21</java.version>` :

```xml
<!-- Versions non gérées par Spring Boot. Mises à jour : ./mvnw versions:display-dependency-updates -->
<duckdb.version>2.0.0.0</duckdb.version>
<poi.version>5.4.1</poi.version>
<commons-csv.version>1.14.0</commons-csv.version>
<commons-text.version>1.13.1</commons-text.version>
<tika.version>3.2.0</tika.version>
<openpdf.version>2.0.3</openpdf.version>
<totp.version>1.7.1</totp.version>
<zxing.version>3.5.3</zxing.version>
<webjars-bootstrap.version>5.3.7</webjars-bootstrap.version>
<webjars-htmx.version>2.0.4</webjars-htmx.version>
<webjars-chartjs.version>4.4.7</webjars-chartjs.version>
<spotless.version>2.44.5</spotless.version>
<jacoco.version>0.8.13</jacoco.version>
```

**Explication.** Ces bibliothèques ne sont pas connues du parent Spring Boot : il faut donc leur donner une version. On les regroupe dans `<properties>` sous forme de variables (`${poi.version}`) pour avoir toutes les versions au même endroit : une mise à jour = une ligne modifiée, sans chercher dans tout le fichier.

**3.2 — Dans `<dependencies>`**, ajoute avant la première dépendance de test :

```xml
<!-- Interface : Bootstrap, HTMX et Chart.js servis par l'application (pas de CDN externe) -->
<dependency>
  <groupId>org.webjars</groupId>
  <artifactId>webjars-locator-lite</artifactId>
</dependency>
<dependency>
  <groupId>org.webjars</groupId>
  <artifactId>bootstrap</artifactId>
  <version>${webjars-bootstrap.version}</version>
</dependency>
<dependency>
  <groupId>org.webjars.npm</groupId>
  <artifactId>htmx.org</artifactId>
  <version>${webjars-htmx.version}</version>
</dependency>
<dependency>
  <groupId>org.webjars.npm</groupId>
  <artifactId>chart.js</artifactId>
  <version>${webjars-chartjs.version}</version>
</dependency>

<!-- Moteur d'analyse -->
<dependency>
  <groupId>org.duckdb</groupId>
  <artifactId>duckdb_jdbc</artifactId>
  <version>${duckdb.version}</version>
</dependency>

<!-- Lecture et export de fichiers -->
<dependency>
  <groupId>org.apache.poi</groupId>
  <artifactId>poi-ooxml</artifactId>
  <version>${poi.version}</version>
</dependency>
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-csv</artifactId>
  <version>${commons-csv.version}</version>
</dependency>
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-text</artifactId>
  <version>${commons-text.version}</version>
</dependency>
<dependency>
  <groupId>org.apache.tika</groupId>
  <artifactId>tika-core</artifactId>
  <version>${tika.version}</version>
</dependency>
<dependency>
  <groupId>com.github.librepdf</groupId>
  <artifactId>openpdf</artifactId>
  <version>${openpdf.version}</version>
</dependency>

<!-- Double authentification -->
<dependency>
  <groupId>dev.samstevens.totp</groupId>
  <artifactId>totp</artifactId>
  <version>${totp.version}</version>
</dependency>
<dependency>
  <groupId>com.google.zxing</groupId>
  <artifactId>javase</artifactId>
  <version>${zxing.version}</version>
</dependency>
```

**Explication de chaque bibliothèque.**

| Bibliothèque | Ce qu'elle fait dans DQ-Analyzer | Exigence |
| --- | --- | --- |
| `webjars-locator-lite` | Permet d'écrire `/webjars/bootstrap/...` sans numéro de version dans les pages ; la mise à jour de Bootstrap ne casse aucune page | ERG-01 |
| `bootstrap` | Styles prêts à l'emploi : boutons, tableaux, formulaires, mise en page | ERG-01 |
| `htmx.org` | Met à jour un morceau de page sans recharger tout l'écran (barre de progression, filtres d'anomalies) sans écrire de JavaScript | EXE-01, RAP-03 |
| `chart.js` | Graphiques des scores par dimension | RAP-01 |
| `duckdb_jdbc` | Base analytique embarquée : le fichier importé devient une table sur disque, interrogée en SQL. Elle traite 2 Go sans saturer la mémoire | PERF-01, PERF-03 |
| `poi-ooxml` | Lit les fichiers Excel en flux et produit l'export Excel surligné | IMP-02, RAP-05 |
| `commons-csv` | Lit les CSV (séparateur, guillemets, encodage) | IMP-03 |
| `commons-text` | Distances de Levenshtein et Jaro-Winkler pour les doublons approchés | UNI-04 |
| `tika-core` | Détecte le vrai type d'un fichier à partir de son contenu, pas de son extension : un `.exe` renommé `.xlsx` est refusé | SEC-03, IMP-07 |
| `openpdf` | Génère la synthèse PDF, sans licence commerciale | RAP-06 |
| `totp` | Génère et vérifie les codes à 6 chiffres de Google ou Microsoft Authenticator | MFA-01 |
| `zxing` (`javase`) | Dessine le QR code que l'utilisateur scanne à l'enrôlement | MFA-02 |

**Pourquoi avant les dépendances de test** : Maven ne l'exige pas, mais regrouper les dépendances de l'application d'un côté et celles des tests (`<scope>test</scope>`) de l'autre rend le fichier lisible.

**3.3 — Dans `<build><plugins>`**, ajoute après `spring-boot-maven-plugin` :

```xml
<!-- Tests d'intégration (*IT.java), lancés par ./mvnw verify -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <executions>
    <execution>
      <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
      </goals>
    </execution>
  </executions>
</plugin>

<!-- Formatage : ./mvnw spotless:apply ; vérifié par ./mvnw verify -->
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>${spotless.version}</version>
  <configuration>
    <java>
      <palantirJavaFormat/>
      <removeUnusedImports/>
      <importOrder/>
    </java>
  </configuration>
  <executions>
    <execution>
      <phase>verify</phase>
      <goals>
        <goal>check</goal>
      </goals>
    </execution>
  </executions>
</plugin>

<!-- Couverture ≥ 70 % sur le moteur de règles et le calcul des scores (MAI-02) -->
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>${jacoco.version}</version>
  <executions>
    <execution>
      <id>prepare-agent</id>
      <goals>
        <goal>prepare-agent</goal>
      </goals>
    </execution>
    <execution>
      <id>report</id>
      <phase>verify</phase>
      <goals>
        <goal>report</goal>
      </goals>
    </execution>
    <execution>
      <id>check</id>
      <phase>verify</phase>
      <goals>
        <goal>check</goal>
      </goals>
      <configuration>
        <rules>
          <rule>
            <element>PACKAGE</element>
            <includes>
              <include>ci.cnam.dqanalyzer.rules*</include>
              <include>ci.cnam.dqanalyzer.execution*</include>
            </includes>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.70</minimum>
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**Explication des trois plugins.** Maven exécute des phases dans un ordre fixe : `compile` → `test` → `package` → `verify`. Lancer une phase exécute toutes celles d'avant. Chaque plugin s'accroche à une phase.

- **Failsafe** : lance les classes `*IT` pendant `verify`. Si un test d'intégration échoue, il laisse d'abord se terminer proprement le conteneur, puis fait échouer le build.
- **Spotless** : formate le code selon un style unique (Palantir, variante lisible du style Google), supprime les imports inutiles et les trie. `spotless:apply` reformate tes fichiers ; pendant `verify`, `check` refuse le build si un fichier n'est pas formaté. Résultat : les diffs Git ne montrent que de vrais changements, jamais des espaces.
- **JaCoCo** : mesure quelles lignes de code les tests exécutent. `prepare-agent` branche la mesure avant les tests, `report` produit le rapport HTML, `check` fait échouer le build si moins de 70 % des lignes des packages `rules` et `execution` sont couvertes. C'est l'exigence MAI-02, vérifiée automatiquement au lieu d'être contrôlée à la main.

**Pourquoi la vérification `dependency:resolve`** : elle télécharge toutes les bibliothèques sans compiler. Si une version n'existe pas ou si le réseau bloque, tu le sais tout de suite, avant d'avoir écrit du code.

Pourquoi les WebJars plutôt qu'un CDN : un serveur interne de la CNAM n'a pas forcément accès à Internet, et la politique de sécurité des pages (CSP) n'autorise que les scripts servis par l'application.

Vérification : `./mvnw -q dependency:resolve` (Windows : `mvnw.cmd -q dependency:resolve`) se termine sans erreur. Le premier lancement télécharge Maven et toutes les bibliothèques : compte quelques minutes. Si une version est introuvable, la commande `./mvnw versions:display-dependency-updates` donne la dernière disponible ; remplace-la dans `<properties>`.

## Étape 4 — Fichiers à la racine

**Dans IntelliJ.** Dans l'onglet Project (**Alt+1**), clic droit sur la racine `dq-analyzer` → **New → File**, et tape le nom complet avec son point (`.gitattributes`, `compose.yaml`…). Pour un fichier dans un sous-dossier qui n'existe pas encore, tape le chemin : `db/init/01-create-database.sh` crée les dossiers au passage. Les fichiers commençant par un point sont bien affichés dans l'onglet Project.

Ces fichiers se créent tous à la racine du projet (à côté de `pom.xml`). Deux existent déjà (`.gitignore`, `.gitattributes`) : remplace leur contenu.

**Principe : tout le projet tourne dans Podman.** Trois conteneurs, décrits dans un seul fichier `compose.yaml` : `postgres` (la base, que tu crées toi-même avec ton script de l'étape 7), `mailpit` (faux serveur e-mail) et `app` (DQ-Analyzer, construit à partir du `Dockerfile`). Une commande les démarre tous : `podman compose up -d --build`. Le même `Dockerfile` sert ensuite au serveur (étape 14).

**4.1 — `Dockerfile`** : fabrique l'image de l'application en deux temps, compilation puis exécution.

```dockerfile
# ---------- Étape 1 : compilation (JDK complet + Maven Wrapper) ----------
FROM docker.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dépendances d'abord : cette couche reste en cache tant que pom.xml ne change pas
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Puis le code, et fabrication du JAR (les tests tournent à part, avec ./mvnw verify)
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package && cp target/dq-analyzer-*.jar /src/app.jar

# ---------- Étape 2 : exécution (JRE seul, utilisateur sans droits) ----------
FROM docker.io/library/eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system dq && useradd --system --gid dq --no-create-home dq \
    && mkdir -p /data /app/logs && chown -R dq:dq /data /app
WORKDIR /app

COPY --from=build --chown=dq:dq /src/app.jar app.jar

USER dq
ENV DQ_STORAGE_PATH=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

**Explication.**

- **Deux étapes (`AS build`, puis une seconde `FROM`)** : la première image contient le JDK et Maven pour compiler ; la seconde ne reçoit que le JAR obtenu (`COPY --from=build`). L'image finale ne contient ni compilateur ni code source : elle est plus petite et offre moins de prise aux attaques. Tu n'as même pas besoin de Java sur la machine qui construit l'image : tout se passe dans Podman.
- **`COPY pom.xml` puis `dependency:go-offline` avant `COPY src/`** : Podman garde en cache chaque étape tant que ses fichiers d'entrée ne changent pas. Quand tu modifies seulement du code Java, il ne retélécharge pas les bibliothèques : la reconstruction passe de plusieurs minutes à quelques dizaines de secondes.
- **`-DskipTests`** : les tests d'intégration démarrent eux-mêmes un conteneur, impossible dans une construction d'image. Les tests se lancent à part, avec `./mvnw verify` (étape 11).
- **`eclipse-temurin:21-jre`, pas la variante Alpine** : DuckDB embarque une bibliothèque native compilée pour Linux standard (glibc) ; Alpine utilise une autre bibliothèque C (musl) et DuckDB y plante au premier appel.
- **`curl`** : installé pour le `HEALTHCHECK`, absent par défaut de l'image JRE. `rm -rf /var/lib/apt/lists/*` supprime le catalogue de paquets téléchargé, inutile ensuite.
- **`useradd` + `USER dq`** : l'application tourne avec un compte sans droits d'administration ; si elle était compromise, l'attaquant ne contrôlerait pas le conteneur. C'est une exigence classique d'une PSSI.
- **`DQ_STORAGE_PATH=/data`** : les fichiers importés et les bases DuckDB vont dans `/data`, monté sur un volume pour survivre à une reconstruction de l'image.
- **`-XX:MaxRAMPercentage=75`** : Java prend au plus 75 % de la mémoire du conteneur et laisse le reste à DuckDB et au système.
- **`HEALTHCHECK`** : Podman interroge `/actuator/health` toutes les 30 secondes et marque le conteneur `healthy` ou `unhealthy` (EXP-02). `start-period=90s` laisse le temps au démarrage de Spring et de Flyway.
- **`exec java`** : Java devient le processus principal du conteneur et reçoit le signal d'arrêt ; l'application se ferme proprement au lieu d'être tuée.
- **Pas de profil fixé dans l'image** : la même image tourne en `dev` sur ton poste et en `prod` sur le serveur ; c'est `compose.yaml` ou le serveur qui choisit, par la variable `SPRING_PROFILES_ACTIVE`.

**4.2 — `compose.yaml`** : les trois conteneurs et leurs liens.

```yaml
# Tout DQ-Analyzer dans Podman : podman compose up -d --build
name: dq-analyzer

services:
  postgres:
    image: docker.io/library/postgres:17-alpine
    container_name: dq-postgres
    environment:
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-postgres_dev}   # super-utilisateur, local uniquement
      DQ_DB_PASSWORD: ${DQ_DB_PASSWORD:-dqanalyzer_dev}       # lu par ton script de création (étape 7)
    volumes:
      - dq-pgdata:/var/lib/postgresql/data
      - ./db/init:/docker-entrypoint-initdb.d:ro,Z             # ton script, exécuté à la première création
    ports:
      - "127.0.0.1:5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 20

  # Faux serveur SMTP : les e-mails (codes 2FA) sont visibles sur http://localhost:8025
  mailpit:
    image: docker.io/axllent/mailpit:latest
    container_name: dq-mailpit
    ports:
      - "127.0.0.1:1025:1025"
      - "127.0.0.1:8025:8025"

  app:
    build: .
    image: localhost/dq-analyzer:dev
    container_name: dq-app
    depends_on:
      postgres:
        condition: service_healthy
      mailpit:
        condition: service_started
    restart: on-failure
    environment:
      SPRING_PROFILES_ACTIVE: dev
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/dqanalyzer
      SPRING_DATASOURCE_USERNAME: dqanalyzer
      SPRING_DATASOURCE_PASSWORD: ${DQ_DB_PASSWORD:-dqanalyzer_dev}
      SPRING_MAIL_HOST: mailpit
      SPRING_MAIL_PORT: "1025"
    ports:
      - "8080:8080"
    volumes:
      - dq-storage:/data

volumes:
  dq-pgdata:
  dq-storage:
```

**Explication.**

- **`name: dq-analyzer`** : nom du projet pour Podman ; les volumes s'appellent `dq-analyzer_dq-pgdata`, etc., sans collision avec d'autres projets.
- **`postgres` sans `POSTGRES_DB` ni `POSTGRES_USER`** : l'image ne crée que le super-utilisateur `postgres`. La base `dqanalyzer` et son utilisateur, c'est ton script qui les crée (étape 7).
- **`${POSTGRES_PASSWORD:-postgres_dev}`** : prend la variable d'environnement si elle existe, sinon la valeur après `:-`. Sur ton poste, les valeurs par défaut suffisent ; ailleurs, on les fournit sans modifier le fichier.
- **`./db/init:/docker-entrypoint-initdb.d`** : l'image PostgreSQL exécute automatiquement les scripts de ce dossier, **une seule fois**, quand le volume de données est vide. `ro` : lecture seule ; `Z` : étiquette de sécurité SELinux, nécessaire sur les serveurs Red Hat, sans effet ailleurs.
- **`127.0.0.1:5432:5432`** : la base n'est joignable que depuis ton propre poste (pour pgAdmin ou `psql`), pas depuis le réseau.
- **`pg_isready -h 127.0.0.1`** : vérifie la base par le réseau. Pendant l'exécution de ton script, PostgreSQL n'écoute pas encore le réseau : le conteneur ne passe `healthy` qu'une fois la base créée.
- **`app` / `build: .`** : Podman construit l'image avec le `Dockerfile` du dossier courant et la nomme `localhost/dq-analyzer:dev`.
- **`depends_on … service_healthy`** : l'application ne démarre qu'après que la base est prête ; `restart: on-failure` la relance si elle s'arrête quand même en erreur (par exemple si la base a mis plus de temps).
- **`SPRING_DATASOURCE_URL: …//postgres:5432/…`** : entre conteneurs, on s'adresse à un service par son nom (`postgres`, `mailpit`), pas par `localhost` : dans le conteneur `app`, `localhost` désigne le conteneur lui-même. Ces variables remplacent les valeurs de `application-dev.yml` (Spring donne priorité aux variables d'environnement).
- **`SPRING_PROFILES_ACTIVE: dev`** : comptes de démonstration et pages rechargées à chaud ; sur le serveur, ce sera `prod`.
- **`dq-storage:/data`** : les fichiers importés et les bases DuckDB survivent à un `podman compose down` ou à une reconstruction ; seul `down -v` les efface.

**Pour coder plus vite (facultatif)** : pendant que tu développes, tu peux ne lancer que la base et l'e-mail (`podman compose up -d postgres mailpit`) et démarrer l'application depuis l'éditeur (`./mvnw spring-boot:run`) : elle se relance en 2 secondes au lieu de reconstruire l'image. `application-dev.yml` pointe déjà sur `localhost:5432` avec le même mot de passe. Pour vérifier « comme en vrai », repasse à `podman compose up -d --build`.

**4.3 — `.gitignore`** : remplace le contenu généré. Aucune donnée métier ni secret ne doit partir dans Git.

```gitignore
# Build
target/
!.mvn/wrapper/maven-wrapper.jar

# IDE
.idea/
*.iml
.vscode/
.classpath
.project
.settings/

# Données locales : jamais de fichier métier ni de base dans Git
storage/
*.duckdb
*.duckdb.wal
logs/
*.log

# Secrets
.env
*.env
!.env.example
application-local.yml

# Claude Code : réglages personnels
.claude/settings.local.json

# OS
.DS_Store
Thumbs.db
```

**Explication.** Chaque ligne désigne des fichiers que Git doit ignorer.

- **`target/`** : tout ce que Maven produit ; il se régénère à chaque build. La ligne `!.mvn/wrapper/maven-wrapper.jar` fait une exception (le `!` annule) pour le petit programme du Maven Wrapper.
- **`.idea/`, `.vscode/`…** : réglages personnels de l'éditeur, différents d'un poste à l'autre.
- **`storage/`, `*.duckdb`** : les fichiers importés et les bases d'analyse contiennent des données d'assurés. Ils ne doivent jamais partir sur une forge : c'est une obligation de la loi n° 2013-450 et de la PSSI (SEC-05).
- **`.env`, `*.env`** : les fichiers de mots de passe. `!.env.example` garde le modèle, qui ne contient aucun secret.
- **`.claude/settings.local.json`** : tes réglages personnels de Claude Code ; le `settings.json` commun, lui, est partagé.

**4.4 — `.gitattributes`** : fins de ligne identiques sous Windows et Linux.

```gitattributes
* text=auto eol=lf
*.cmd text eol=crlf
*.bat text eol=crlf
*.jar binary
*.xlsx binary
*.xls binary
*.parquet binary
```

**Explication.** Complète le réglage `autocrlf` de l'étape 1, mais au niveau du projet : il s'applique à toute personne qui clone le dépôt, quel que soit son réglage personnel. `eol=lf` force les fins de ligne Linux, sauf pour les scripts Windows (`.cmd`, `.bat`) qui ont besoin de CRLF. `binary` empêche Git de modifier ou de comparer ligne à ligne les fichiers Excel, Parquet et JAR, ce qui les corromprait.

**4.5 — `.editorconfig`** : même indentation dans tous les éditeurs.

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

[*.{xml,yml,yaml,html,json,sql}]
indent_size = 2

[*.{md,csv}]
trim_trailing_whitespace = false

[*.{cmd,bat}]
end_of_line = crlf
```

**Explication.** IntelliJ, VS Code et la plupart des éditeurs lisent ce fichier automatiquement : encodage UTF-8 (indispensable pour les accents), 4 espaces d'indentation pour Java, 2 pour XML, YAML, HTML et SQL. Les espaces en fin de ligne sont gardés dans les CSV et le Markdown, car ils peuvent y avoir un sens : le jeu de test de l'étape 10 contient justement une valeur faite d'espaces.

**4.6 — `.env.example`** : modèle des variables de production. Le vrai fichier (`prod.env`) reste hors de Git.

```bash
SPRING_PROFILES_ACTIVE=prod
DQ_DB_URL=jdbc:postgresql://serveur-bdd:5432/dqanalyzer
DQ_DB_USER=dqanalyzer
DQ_DB_PASSWORD=
DQ_MAIL_HOST=
DQ_MAIL_PORT=25
DQ_STORAGE_PATH=/data
```

**Explication.** Ce modèle liste les variables que l'exploitant doit renseigner sur le serveur. Il est versionné pour que personne n'ait à deviner leur nom ; le vrai fichier, rempli avec les mots de passe, n'est jamais versionné. C'est le principe de la configuration externalisée demandé par EXP-01 : le même JAR tourne partout, seul l'environnement change.

**4.7 — `CLAUDE.md` et le cahier des charges**

1. Copie le `CLAUDE.md` que je t'ai fourni à la racine du projet.
2. Crée le dossier `docs` et copies-y le cahier des charges sous le nom exact `docs/CAHIER_DES_CHARGES_V1.md` (le `CLAUDE.md` y renvoie).

**Explication.** Claude Code lit le `CLAUDE.md` au début de chaque session : il y trouve la stack, les commandes et les règles du projet sans que tu les répètes. Le `CLAUDE.md` renvoie au cahier des charges par son chemin exact ; si le fichier est ailleurs ou porte un autre nom, Claude ne le trouvera pas quand tu cites une exigence (« fais IMP-03 »).

**4.8 — `.claude/settings.json`** : autorise d'avance les commandes sûres pour que Claude Code ne te demande pas confirmation à chaque fois, et lui interdit de lire les secrets et les fichiers métier.

```json
{
  "$schema": "https://json.schemastore.org/claude-code-settings.json",
  "permissions": {
    "allow": [
      "Bash(./mvnw:*)",
      "Bash(mvnw.cmd:*)",
      "Bash(podman compose:*)",
      "Bash(podman ps:*)",
      "Bash(podman logs:*)",
      "Bash(git status:*)",
      "Bash(git diff:*)",
      "Bash(git log:*)",
      "Bash(git add:*)",
      "Bash(git commit:*)",
      "Bash(git switch:*)",
      "Bash(git branch:*)"
    ],
    "ask": [
      "Bash(git push:*)",
      "Bash(podman compose down:*)"
    ],
    "deny": [
      "Read(./.env)",
      "Read(./**/*.env)",
      "Read(./storage/**)",
      "Bash(rm -rf:*)",
      "Bash(git push --force:*)"
    ]
  }
}
```

**Explication des trois listes.**

- **`allow`** : commandes que Claude Code exécute sans te demander, parce qu'elles ne détruisent rien : construire et tester (`./mvnw`), gérer les conteneurs, lire l'état Git, commiter en local. C'est ce qui te fait gagner le plus de temps.
- **`ask`** : commandes qui demandent toujours ta confirmation. `git push` envoie ton code à l'extérieur ; `podman compose down` peut effacer la base locale.
- **`deny`** : interdit à Claude de lire les fichiers de secrets et le dossier `storage/` (données d'assurés), et de lancer des suppressions récursives ou un `push --force` qui écraserait l'historique distant.

Le `:*` signifie « avec n'importe quels arguments » : `Bash(./mvnw:*)` couvre `./mvnw test`, `./mvnw verify`, etc.

Vérification (le dossier db/init viendra à l'étape 7) : à la racine, tu as maintenant `compose.yaml`, `Dockerfile`, `.gitignore`, `.gitattributes`, `.editorconfig`, `.env.example`, `CLAUDE.md`, `docs/CAHIER_DES_CHARGES_V1.md` et `.claude/settings.json`.

## Étape 5 — Arborescence des packages

**Dans IntelliJ.** Clic droit sur `src/main/java/ci/cnam/dqanalyzer` → **New → Package**, et tape le nom relatif, par exemple `rules.evaluators` : les deux niveaux sont créés d'un coup. Pour les classes, le plus rapide : copie tout le code d'une classe depuis ce guide, sélectionne le package de destination dans l'onglet Project et colle (**Ctrl+V**) : IntelliJ crée le fichier avec le bon nom. Si la ligne `package` du code ne correspond pas au dossier, il la souligne en rouge : **Alt+Entrée** propose de corriger. Si l'onglet affiche `ci.cnam.dqanalyzer.rules` sur une seule ligne au lieu de dossiers imbriqués, c'est l'option d'affichage **Compact Middle Packages** (icône ⚙ de l'onglet), sans effet sur les fichiers.

Le socle crée 4 des 11 modules du `CLAUDE.md` : `rules`, `execution`, `security` et `shared`. Les autres (`importing`, `profiling`, `templates`, `reference`, `reporting`, `history`, `audit`) arriveront avec leurs lots. Voici l'état visé à la fin de l'étape 10, fichier par fichier ; ceux marqués « généré » viennent de start.spring.io.

```text
dq-analyzer/
├── src/main/java/ci/cnam/dqanalyzer/
│   ├── DqAnalyzerApplication.java                 (généré)
│   ├── shared/                                    module ouvert, sans logique métier
│   │   ├── package-info.java
│   │   ├── duckdb/DuckDbIdentifiers.java
│   │   ├── duckdb/SqlFragment.java
│   │   └── web/HomeController.java
│   ├── security/
│   │   ├── Role.java                              public (racine du module)
│   │   ├── api/LoginController.java
│   │   ├── domain/AppUser.java
│   │   └── infra/AppUserRepository.java, DatabaseUserDetailsService.java, SecurityConfig.java
│   ├── rules/
│   │   ├── Dimension.java, Severity.java, Normalization.java
│   │   ├── RuleDefinition.java, RuleResult.java, RuleEvaluationException.java
│   │   ├── Anomaly.java, AnomalySink.java, Dataset.java, ReferenceProvider.java
│   │   ├── ParamSchema.java, RuleEvaluator.java, RuleEvaluatorRegistry.java
│   │   └── evaluators/RowPredicateEvaluator.java, Cmp01RequiredValueEvaluator.java
│   └── execution/
│       ├── RuleStatus.java
│       └── domain/Scoring.java
├── src/main/resources/
│   ├── application.yml, application-dev.yml, application-prod.yml
│   ├── messages.properties
│   ├── db/migration/V1__init_schema.sql
│   ├── db/dev/afterMigrate.sql
│   ├── static/css/app.css
│   └── templates/fragments/layout.html, home.html, security/login.html
└── src/test/
    ├── java/ci/cnam/dqanalyzer/
    │   ├── DqAnalyzerApplicationIT.java           (généré, renommé, puis remplacé)
    │   ├── TestcontainersConfiguration.java       (généré)
    │   ├── TestDqAnalyzerApplication.java         (généré)
    │   ├── ModularityTest.java
    │   ├── testsupport/TestDatasets.java, Rules.java
    │   ├── rules/RuleEvaluatorRegistryTest.java, NormalizationTest.java
    │   ├── rules/evaluators/Cmp01RequiredValueEvaluatorTest.java
    │   └── execution/domain/ScoringTest.java
    └── resources/datasets/cmp01_num_secu.csv
```

Règle à retenir : dans un module, seules les classes à la racine (`rules/RuleEvaluator.java`, `security/Role.java`…) sont visibles des autres modules. Tout ce qui est dans `api/`, `domain/`, `infra/` ou `evaluators/` est interne ; le test `ModularityTest` échoue si un autre module s'en sert.

**Explication de l'organisation.**

- **Un module = une fonction métier** (`rules`, `security`…), pas une couche technique. Quand tu travailles sur les règles, tout ce qui les concerne est au même endroit. C'est le « monolithe modulaire » du CDC §9 : une seule application à déployer, mais découpée comme si c'étaient des services séparés, ce qui permettra d'en extraire un plus tard si besoin.
- **À l'intérieur d'un module, trois dossiers** : `api/` reçoit les requêtes (contrôleurs web), `domain/` contient la logique et les objets métier, `infra/` parle à l'extérieur (base de données, fichiers, configuration technique). La logique métier ne dépend ainsi ni de l'écran ni de la base, et se teste seule.
- **Pourquoi des frontières contrôlées** : sans règle, chaque module finit par utiliser les classes internes des autres, et modifier une classe en casse dix. Spring Modulith considère qu'un module expose seulement les classes de sa racine, et `ModularityTest` le vérifie à chaque build. `shared` est déclaré « ouvert » (étape 8.1) : tous les modules peuvent utiliser ses outils.
- **`src/main` et `src/test`** : le code livré d'un côté, les tests de l'autre, avec la même arborescence de packages. Un test placé dans le même package que la classe testée peut accéder à ses éléments non publics.

Crée les dossiers d'un coup depuis la racine du projet.

Linux / macOS / Git Bash :

```bash
B=src/main/java/ci/cnam/dqanalyzer
T=src/test/java/ci/cnam/dqanalyzer
mkdir -p $B/shared/duckdb $B/shared/web $B/security/api $B/security/domain $B/security/infra \
         $B/rules/evaluators $B/execution/domain \
         src/main/resources/db/migration src/main/resources/db/dev \
         src/main/resources/static/css src/main/resources/templates/fragments src/main/resources/templates/security \
         $T/testsupport $T/rules/evaluators $T/execution/domain src/test/resources/datasets
```

Windows PowerShell :

```powershell
$B = "src/main/java/ci/cnam/dqanalyzer"; $T = "src/test/java/ci/cnam/dqanalyzer"
"$B/shared/duckdb","$B/shared/web","$B/security/api","$B/security/domain","$B/security/infra",
"$B/rules/evaluators","$B/execution/domain","src/main/resources/db/migration","src/main/resources/db/dev",
"src/main/resources/static/css","src/main/resources/templates/fragments","src/main/resources/templates/security",
"$T/testsupport","$T/rules/evaluators","$T/execution/domain","src/test/resources/datasets" |
  ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }
```

**Explication des commandes.** `B` et `T` sont des variables qui évitent de retaper le long chemin des packages. `mkdir -p` (et `New-Item -Force` sous PowerShell) crée tous les dossiers intermédiaires d'un coup et ne signale pas d'erreur si un dossier existe déjà : tu peux relancer la commande sans risque. Tu peux aussi créer les dossiers dans IntelliJ (clic droit → *New → Package*) ; le résultat est le même.

Vérification : dans l'éditeur, l'arbre `src/main/java/ci/cnam/dqanalyzer` contient les dossiers `shared`, `security`, `rules` et `execution`.

## Étape 6 — Configuration Spring et textes de l'interface

Trois fichiers de configuration : un commun, un par environnement. Le profil `dev` est actif par défaut, donc un simple lancement depuis l'éditeur fonctionne. En production, tout ce qui est sensible vient des variables d'environnement.

**Explication du principe des profils.** Spring lit d'abord `application.yml`, puis le fichier du profil actif (`application-dev.yml` ou `application-prod.yml`), dont les valeurs remplacent celles du fichier commun. On écrit donc une seule fois ce qui est identique partout, et seulement les différences par environnement. Le JAR est le même en dev et en prod : seul le profil change.

**6.1 — `src/main/resources/application.yml`**

```yaml
# Configuration commune à tous les profils. Valeurs propres à un environnement : application-<profil>.yml
spring:
  application:
    name: dq-analyzer
  profiles:
    default: dev
  threads:
    virtual:
      enabled: true
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate          # le schéma est géré par Flyway, jamais par Hibernate
  flyway:
    locations: classpath:db/migration
  messages:
    basename: messages
    fallback-to-system-locale: false
  servlet:
    multipart:
      max-file-size: 20MB         # un morceau d'upload (10 Mo) + marge
      max-request-size: 25MB

server:
  servlet:
    session:
      timeout: 30m                # SEC-01
      cookie:
        http-only: true
        same-site: lax
  error:
    include-stacktrace: never
    include-message: never

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
  health:
    mail:
      enabled: false              # à réactiver quand le SMTP interne sera connu

dq:
  storage:
    path: ${DQ_STORAGE_PATH:./storage}   # fichiers importés et bases DuckDB (3 × taille max par analyse)
  upload:
    max-file-size: 2GB                   # PERF-02, paramétrable
    chunk-size: 10MB
  retention:
    days: 90                             # SEC-04
```

**Explication ligne par ligne.**

- **`profiles.default: dev`** : profil utilisé quand aucun n'est précisé. Un lancement depuis l'éditeur ou un simple `./mvnw spring-boot:run` part donc en dev. En prod, le Dockerfile impose `prod`.
- **`threads.virtual.enabled`** : utilise les threads virtuels de Java 21. Chaque requête ou analyse en tâche de fond reçoit un thread très léger ; l'application supporte beaucoup d'attentes simultanées (base, disque) sans réglage de pool.
- **`open-in-view: false`** : par défaut, Spring garde la connexion à la base ouverte pendant tout l'affichage de la page, ce qui cache des requêtes lentes et épuise les connexions. On la ferme dès la fin du traitement.
- **`ddl-auto: validate`** : Hibernate pourrait créer ou modifier les tables tout seul, mais sans historique ni contrôle. Ici il vérifie seulement que les entités correspondent au schéma créé par Flyway, et refuse de démarrer sinon.
- **`flyway.locations`** : le dossier où Flyway cherche les scripts SQL à exécuter.
- **`messages`** : fichier des textes de l'interface. `fallback-to-system-locale: false` empêche Spring de chercher une traduction anglaise si le poste est en anglais : l'interface reste en français (ERG-01).
- **`multipart`** : taille maximale d'une requête d'envoi de fichier. 20 Mo suffit car les gros fichiers seront envoyés par morceaux de 10 Mo (PERF-02) ; une limite basse protège le serveur contre un envoi géant d'un seul bloc.
- **`session.timeout: 30m`** : déconnexion après 30 minutes d'inactivité (SEC-01).
- **`http-only`, `same-site: lax`** : le cookie de session est invisible pour le JavaScript de la page (un script injecté ne peut pas le voler) et n'est pas envoyé par les formulaires d'autres sites.
- **`include-stacktrace: never`, `include-message: never`** : une erreur n'affiche jamais de détail technique à l'utilisateur, qui pourrait renseigner un attaquant sur le code.
- **`management`** : l'Actuator expose seulement `health` et `info`, sans détail (pas de nom de base ni d'espace disque visible).
- **`health.mail.enabled: false`** : sinon `/actuator/health` passerait à `DOWN` tant qu'aucun SMTP ne répond, et l'application serait déclarée en panne alors qu'elle fonctionne.
- **`dq.*`** : les réglages propres à l'outil. `${DQ_STORAGE_PATH:./storage}` signifie « la variable d'environnement si elle existe, sinon `./storage` ». Ils seront lus par le code des prochains lots ; les mettre ici dès maintenant évite toute valeur codée en dur.

**6.2 — `src/main/resources/application-dev.yml`**

```yaml
# Profil dev : services lancés par "podman compose up -d". Valeurs locales jetables uniquement.
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dqanalyzer
    username: dqanalyzer
    password: dqanalyzer_dev
  flyway:
    locations: classpath:db/migration,classpath:db/dev   # + comptes de démonstration
  mail:
    host: localhost
    port: 1025                    # MailPit ; e-mails visibles sur http://localhost:8025
  thymeleaf:
    cache: false

logging:
  level:
    ci.cnam.dqanalyzer: DEBUG
```

**Explication.** `datasource` donne l'adresse de la base lancée par `compose.yaml`, avec les mêmes identifiants. `flyway.locations` ajoute le dossier `db/dev` : seuls les postes de développement chargent les comptes de démonstration. `mail` pointe vers MailPit. `thymeleaf.cache: false` relit les pages HTML à chaque requête : tu modifies un template et tu rafraîchis le navigateur, sans redémarrer. `DEBUG` sur `ci.cnam.dqanalyzer` affiche les messages détaillés de notre code seulement, pas ceux de toutes les bibliothèques.

**6.3 — `src/main/resources/application-prod.yml`**

```yaml
# Profil prod : tout ce qui est sensible ou propre au serveur vient des variables d'environnement.
spring:
  datasource:
    url: ${DQ_DB_URL}
    username: ${DQ_DB_USER}
    password: ${DQ_DB_PASSWORD}
  mail:
    host: ${DQ_MAIL_HOST}
    port: ${DQ_MAIL_PORT:25}

server:
  forward-headers-strategy: native   # HTTPS terminé par le reverse proxy (SEC-03)
  servlet:
    session:
      cookie:
        secure: true

logging:
  file:
    name: ${DQ_LOG_FILE:/app/logs/dq-analyzer.log}   # EXP-02
  logback:
    rollingpolicy:
      max-file-size: 20MB
      max-history: 30
```

**Explication.** `${DQ_DB_URL}` sans valeur par défaut : si l'exploitant oublie une variable, l'application refuse de démarrer au lieu de tourner avec une mauvaise base. `forward-headers-strategy: native` : en production, le HTTPS est géré par un serveur frontal (reverse proxy) qui transmet ensuite la requête ; ce réglage fait confiance aux en-têtes qu'il ajoute, pour que l'application sache que l'utilisateur est en HTTPS et construise les bons liens. `cookie.secure: true` : le cookie de session ne circule qu'en HTTPS (SEC-03). `logging.file` + `rollingpolicy` : les logs vont dans un fichier, découpé à 20 Mo et conservé 30 fichiers, pour ne jamais remplir le disque (EXP-02).

**6.4 — `src/main/resources/messages.properties`** : tous les textes visibles par l'utilisateur, en français.

```properties
# Textes de l'interface (français). Jamais de texte en dur dans les templates ou le code.
app.name=DQ-Analyzer
app.tagline=Qualité des données Master Data — Service Gouvernance des Données

nav.home=Accueil
nav.logout=Se déconnecter

login.title=Connexion
login.username=Identifiant
login.password=Mot de passe
login.submit=Se connecter
login.error=Identifiant ou mot de passe incorrect.
login.logout=Vous êtes déconnecté.

home.title=Accueil
home.welcome=Bienvenue, {0}
home.intro=Chargez un fichier, appliquez un modèle de règles et obtenez un rapport de qualité.
home.soon=Les fonctions d'import et d'analyse arrivent dans les prochains lots.

dimension.COMPLETENESS=Complétude
dimension.UNIQUENESS=Unicité
dimension.VALIDITY=Validité
dimension.ACCURACY=Exactitude
dimension.CONSISTENCY=Cohérence
dimension.TIMELINESS=Actualité
dimension.REFERENTIAL_INTEGRITY=Intégrité référentielle

severity.BLOCKING=Bloquante
severity.MAJOR=Majeure
severity.MINOR=Mineure

status.OK=OK
status.ALERT=Alerte
status.KO=KO
```

**Explication.** Chaque ligne associe une clé (`login.title`) à un texte. Les pages appellent la clé (`#{login.title}`), jamais le texte. Avantages : un libellé se corrige à un seul endroit, le métier peut relire tous les textes dans un seul fichier, et une traduction future se ferait en ajoutant `messages_en.properties`. `{0}` est un emplacement remplacé par une valeur, par exemple le nom de l'utilisateur dans « Bienvenue, {0} ». Les clés `dimension.*`, `severity.*` et `status.*` donnent le libellé français des valeurs stockées en anglais dans le code et la base.

Piège à connaître : dans un message qui contient un paramètre `{0}`, une apostrophe doit être doublée (`l''import`), sinon le texte s'affiche mal. Les messages sans paramètre s'écrivent normalement.

Dans IntelliJ, enregistre ce fichier en UTF-8 : *Settings → Editor → File Encodings → Default encoding for properties files = UTF-8*.

Vérification : les 4 fichiers sont dans `src/main/resources`, et l'ancien `application.yaml` généré a bien été supprimé (deux fichiers de configuration en même temps se mélangent).

## Étape 7 — Base PostgreSQL (créée par toi), schéma et comptes de démonstration

**Dans IntelliJ.** Avant de créer le script `db/init/01-create-database.sh`, vérifie que le séparateur de lignes est `LF` (étape 2 bis.3) ; regarde-le en bas à droite une fois le fichier ouvert. Les fichiers `.sql` s'ouvrent avec la coloration SQL ; IntelliJ peut proposer de choisir un « dialecte » : choisis PostgreSQL pour les migrations Flyway.

La base se fait en deux parties : **toi**, tu crées la base, son utilisateur et ses droits, avec un script exécuté par le conteneur `postgres` ; **Flyway** crée ensuite les tables, au démarrage du conteneur `app`.

**Explication : pourquoi ne pas créer aussi les tables à la main.** Si tu exécutes le script des tables toi-même, Flyway ne le sait pas : au démarrage, il voit une base sans historique, rejoue `V1` et échoue sur `relation "app_user" already exists`. Surtout, tu perdrais le suivi des versions du schéma, qui garantit que ton poste, la recette et la production ont exactement les mêmes tables. Tu gardes la main sur leur contenu : c'est toi qui écris `V1`, `V2`…

**7.0 — Le script de création de ta base : `db/init/01-create-database.sh`**

Crée le dossier `db/init` à la racine du projet (à côté de `compose.yaml`), puis ce fichier :

```bash
#!/bin/sh
# Création de la base DQ-Analyzer. Exécuté automatiquement par le conteneur postgres,
# UNE seule fois : au premier démarrage, quand le volume de données est vide.
set -e

psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres \
     -v app_password="$DQ_DB_PASSWORD" <<'EOSQL'
-- 1. L'utilisateur applicatif : il peut se connecter, sans aucun pouvoir d'administration
CREATE ROLE dqanalyzer WITH LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE;

-- 2. La base, qui lui appartient, encodée en UTF-8
CREATE DATABASE dqanalyzer OWNER dqanalyzer ENCODING 'UTF8' TEMPLATE template0;

-- 3. Personne d'autre ne peut s'y connecter
REVOKE ALL ON DATABASE dqanalyzer FROM PUBLIC;

-- 4. Dans la nouvelle base : le schéma public appartient à l'utilisateur applicatif
\c dqanalyzer
ALTER SCHEMA public OWNER TO dqanalyzer;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
EOSQL
```

**Explication ligne par ligne.**

- **Un script `.sh` plutôt qu'un `.sql`** : le mot de passe ne doit pas être écrit dans un fichier versionné. Le script le lit dans la variable `DQ_DB_PASSWORD`, que `compose.yaml` donne au conteneur (valeur locale par défaut sur ton poste, vraie valeur sur le serveur).
- **`set -e`** : le script s'arrête à la première erreur au lieu de continuer à moitié.
- **`psql … --username postgres --dbname postgres`** : se connecte en super-utilisateur à la base d'administration, la seule qui existe au départ. `ON_ERROR_STOP=1` : une erreur SQL arrête tout, et le conteneur le signale dans ses logs.
- **`-v app_password=…` puis `:'app_password'`** : `psql` remplace `:'app_password'` par la valeur, entourée d'apostrophes et protégée correctement, même si le mot de passe contient des caractères spéciaux.
- **`<<'EOSQL' … EOSQL`** : tout ce qui est entre les deux marqueurs est envoyé à `psql` comme s'il était tapé au clavier. Les apostrophes autour du premier `EOSQL` empêchent le shell de modifier le texte (les `$` et les `\` restent tels quels).
- **`CREATE ROLE … LOGIN`** : dans PostgreSQL, un utilisateur est un « rôle » autorisé à se connecter. `NOSUPERUSER NOCREATEDB NOCREATEROLE` applique le moindre privilège : si l'application était piratée, ce compte ne pourrait ni créer d'autres bases ou comptes, ni lire les autres bases du serveur.
- **`OWNER dqanalyzer`** : l'utilisateur applicatif est propriétaire de sa base ; Flyway peut y créer les tables sans droit supplémentaire.
- **`ENCODING 'UTF8' TEMPLATE template0`** : les accents (KOUAMÉ) sont stockés correctement. `template0` est la base modèle vierge, obligatoire pour choisir l'encodage.
- **`REVOKE ALL ON DATABASE … FROM PUBLIC`** : par défaut, tout utilisateur du serveur peut se connecter à toute base ; sur un serveur partagé de la DSI, cette ligne réserve la base à DQ-Analyzer.
- **`\c dqanalyzer`** : commande de `psql` qui se connecte à la nouvelle base ; les deux lignes suivantes s'y appliquent. Le schéma `public` est le dossier où vivront les tables : seul l'utilisateur applicatif peut y créer et y lire des objets.

**Quand il s'exécute.** Le dossier `db/init` est monté dans le conteneur sous `/docker-entrypoint-initdb.d` (voir `compose.yaml`). L'image PostgreSQL y exécute les scripts dans l'ordre alphabétique (d'où le préfixe `01-`), **uniquement quand le volume de données est vide**. Si tu modifies le script après un premier lancement, il ne rejoue pas : en dev, `podman compose down -v` puis `podman compose up -d` repartent d'une base vide.

**Contrôle après le premier lancement (étape 11)** :

```bash
podman exec -it dq-postgres psql -U postgres -c "\du"               # l'utilisateur dqanalyzer existe
podman exec -it dq-postgres psql -U postgres -c "\l dqanalyzer"     # la base existe, propriétaire dqanalyzer
podman exec -it dq-postgres psql -U dqanalyzer -d dqanalyzer -c "\dt"   # 14 tables, une fois l'app démarrée
```

**Explication.** `podman exec -it dq-postgres psql …` ouvre le client PostgreSQL déjà installé dans le conteneur : rien à installer sur ton poste. `\du` liste les utilisateurs, `\l` les bases, `\dt` les tables. Les 14 tables sont les 13 de `V1` et `flyway_schema_history`, le registre où Flyway note les scripts déjà exécutés.

Si le fichier a été créé sous Windows avec des fins de ligne CRLF, le conteneur échoue avec `$'\r': command not found`. Le `.gitattributes` de l'étape 4 l'évite pour les fichiers commités ; dans VS Code ou IntelliJ, vérifie que la barre d'état affiche `LF` pour ce fichier.

La migration V1 crée 13 tables : les 11 du modèle de données (CDC §9.4), plus deux ajouts : `mfa_recovery_code` (codes de secours, MFA-05) et `run_reference` (versions des tables de référence utilisées par une analyse, TRA-02). Hibernate ne touche jamais au schéma : il vérifie seulement qu'il correspond aux entités Java.

**Explication de Flyway.** Au démarrage, Flyway regarde dans la table `flyway_schema_history` quels scripts ont déjà été exécutés sur cette base, puis exécute les nouveaux dans l'ordre de leur numéro (`V1`, `V2`…). Chaque base (ton poste, la recette, la prod) arrive ainsi exactement au même schéma, et l'historique des changements est dans Git. Flyway garde aussi une empreinte de chaque script : si tu modifies `V1` après son exécution, il refuse de démarrer. C'est voulu : on ne réécrit jamais l'histoire, on ajoute une `V2`.

Deux choix de conception à connaître :

- **Versions de règles** : chaque modification d'une règle crée une nouvelle ligne dans `dq_rule` avec le même `rule_code` et `version + 1`. Une seule ligne par code porte `is_current = true` (index unique partiel). Un résultat d'analyse pointe la ligne exacte (`rule_result.rule_id`) : une analyse ancienne se rouvre avec ses règles d'époque.
- **Modèles** : `template_rule` référence le `rule_code`, pas une version ; un modèle utilise donc toujours la version courante.

**7.1 — `src/main/resources/db/migration/V1__init_schema.sql`** (deux tirets bas après `V1`)

```sql
-- V1 : schéma initial DQ-Analyzer (CDC §9.4)
-- Règle : ne jamais modifier ce fichier une fois commité. Toute évolution = nouvelle migration V2__..., V3__...

-- Utilisateurs et double authentification (USR-*, MFA-*)
CREATE TABLE app_user (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login               VARCHAR(64)  NOT NULL UNIQUE,
    full_name           VARCHAR(200) NOT NULL,
    email               VARCHAR(254) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    role                VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'ANALYST', 'READER')),
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    mfa_method          VARCHAR(10)  CHECK (mfa_method IN ('TOTP', 'EMAIL')),
    totp_secret         VARCHAR(255),
    mfa_enrolled        BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_mfa_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE mfa_recovery_code (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    code_hash  VARCHAR(100) NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_mfa_recovery_code_user ON mfa_recovery_code (user_id);

-- Règles de qualité, versionnées (REG-04) : une ligne par version, rule_code stable entre versions
CREATE TABLE dq_rule (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_code      VARCHAR(30)  NOT NULL,
    version        INTEGER      NOT NULL,
    is_current     BOOLEAN      NOT NULL DEFAULT TRUE,
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    dimension      VARCHAR(30)  NOT NULL CHECK (dimension IN ('COMPLETENESS', 'UNIQUENESS', 'VALIDITY', 'ACCURACY',
                                                              'CONSISTENCY', 'TIMELINESS', 'REFERENTIAL_INTEGRITY')),
    rule_type      VARCHAR(10)  NOT NULL,
    target_columns JSONB        NOT NULL,
    params         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    normalization  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    severity       VARCHAR(10)  NOT NULL CHECK (severity IN ('BLOCKING', 'MAJOR', 'MINOR')),
    threshold      NUMERIC(5, 2) NOT NULL CHECK (threshold BETWEEN 0 AND 100),
    message        VARCHAR(500),
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by     BIGINT       NOT NULL REFERENCES app_user (id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_dq_rule_code_version UNIQUE (rule_code, version)
);
CREATE UNIQUE INDEX uq_dq_rule_current ON dq_rule (rule_code) WHERE is_current;
CREATE INDEX idx_dq_rule_dimension ON dq_rule (dimension);

-- Modèles d'analyse (MOD-*) ; un modèle pointe le code de règle et utilise sa version courante
CREATE TABLE analysis_template (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              VARCHAR(200) NOT NULL UNIQUE,
    description       TEXT,
    expected_columns  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    dimension_weights JSONB        NOT NULL DEFAULT '{}'::jsonb,
    import_settings   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    column_mapping    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_by        BIGINT       NOT NULL REFERENCES app_user (id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE template_rule (
    template_id BIGINT      NOT NULL REFERENCES analysis_template (id) ON DELETE CASCADE,
    rule_code   VARCHAR(30) NOT NULL,
    position    INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (template_id, rule_code)
);

-- Tables de référence versionnées (REF-*)
CREATE TABLE reference_table (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    version    INTEGER      NOT NULL,
    columns    JSONB        NOT NULL,
    row_count  BIGINT       NOT NULL DEFAULT 0,
    loaded_by  BIGINT       NOT NULL REFERENCES app_user (id),
    loaded_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_reference_table_name_version UNIQUE (name, version)
);

CREATE TABLE reference_value (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference_table_id BIGINT NOT NULL REFERENCES reference_table (id) ON DELETE CASCADE,
    row_values         JSONB  NOT NULL
);
CREATE INDEX idx_reference_value_table ON reference_value (reference_table_id);

-- Fichiers importés (IMP-06)
CREATE TABLE source_file (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    original_name   VARCHAR(500) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    sha256          CHAR(64)     NOT NULL,
    format          VARCHAR(20)  NOT NULL,
    import_settings JSONB        NOT NULL DEFAULT '{}'::jsonb,
    row_count       BIGINT,
    column_count    INTEGER,
    storage_path    VARCHAR(1000),
    uploaded_by     BIGINT       NOT NULL REFERENCES app_user (id),
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    purged_at       TIMESTAMPTZ
);
CREATE INDEX idx_source_file_sha256 ON source_file (sha256);

-- Exécutions d'analyse et résultats (EXE-*, RAP-*, HIS-*)
CREATE TABLE analysis_run (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_file_id BIGINT      NOT NULL REFERENCES source_file (id),
    template_id    BIGINT      REFERENCES analysis_template (id),
    launched_by    BIGINT      NOT NULL REFERENCES app_user (id),
    status         VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    progress       INTEGER     NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    result_status  VARCHAR(10) CHECK (result_status IN ('OK', 'ALERT', 'KO')),
    global_score   NUMERIC(5, 2),
    error_message  VARCHAR(1000),
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ
);
CREATE INDEX idx_analysis_run_started ON analysis_run (started_at DESC);
CREATE INDEX idx_analysis_run_template ON analysis_run (template_id);

-- Versions exactes des tables de référence utilisées par une analyse (TRA-02)
CREATE TABLE run_reference (
    run_id             BIGINT NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    reference_table_id BIGINT NOT NULL REFERENCES reference_table (id),
    PRIMARY KEY (run_id, reference_table_id)
);

-- rule_id pointe la ligne de version exacte de la règle (TRA-02)
CREATE TABLE rule_result (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id           BIGINT        NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    rule_id          BIGINT        NOT NULL REFERENCES dq_rule (id),
    checked_count    BIGINT        NOT NULL,
    conforming_count BIGINT        NOT NULL,
    score            NUMERIC(5, 2),
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('OK', 'ALERT', 'KO')),
    duplicate_groups BIGINT,
    CONSTRAINT uq_rule_result_run_rule UNIQUE (run_id, rule_id)
);

-- Anomalies ligne à ligne (RAP-03, RAP-04) ; purgées après la durée de rétention (SEC-04)
CREATE TABLE anomaly (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    rule_id         BIGINT       NOT NULL REFERENCES dq_rule (id),
    row_num         BIGINT       NOT NULL,
    column_name     VARCHAR(255),
    cell_value      TEXT,
    message         VARCHAR(500) NOT NULL,
    duplicate_group BIGINT
);
CREATE INDEX idx_anomaly_run_rule ON anomaly (run_id, rule_id);
CREATE INDEX idx_anomaly_run_row ON anomaly (run_id, row_num);

-- Journal d'audit (TRA-01, MFA-07) ; user_login conservé en texte pour survivre à la suppression d'un compte
CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    user_login  VARCHAR(64),
    action      VARCHAR(50)  NOT NULL,
    object_type VARCHAR(50),
    object_id   VARCHAR(64),
    ip_address  VARCHAR(45),
    detail      JSONB
);
CREATE INDEX idx_audit_log_occurred ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_user ON audit_log (user_login);
```

**Explication des choix SQL.**

- **`BIGINT GENERATED ALWAYS AS IDENTITY`** : identifiant numérique créé automatiquement par PostgreSQL, à la norme SQL actuelle (remplace l'ancien `SERIAL`). `BIGINT` évite tout dépassement, même avec des millions d'anomalies.
- **`TIMESTAMPTZ`** : date et heure avec fuseau horaire. Une analyse lancée à 15 h à Abidjan reste juste si le serveur ou un utilisateur change de fuseau. `DEFAULT now()` remplit la date automatiquement.
- **`CHECK (role IN (...))`** : la base refuse toute valeur hors de la liste, même si un bug du code en envoie une. Les valeurs correspondent exactement aux `enum` Java (`ADMIN`, `BLOCKING`…).
- **`JSONB`** : stocke un objet JSON indexable. Les paramètres d'une règle varient selon son type (une liste pour VAL-05, un min et un max pour VAL-04) : une colonne JSON évite d'ajouter une colonne par paramètre, et un nouveau type de règle ne change pas la base (CDC §9.3).
- **`REFERENCES ... ON DELETE CASCADE`** : la clé étrangère garantit qu'une anomalie pointe une analyse qui existe. `CASCADE` supprime automatiquement les anomalies quand on supprime leur analyse, ce qui servira à la purge à 90 jours (SEC-04). Sans `CASCADE` (par exemple vers `dq_rule`), la base interdit de supprimer une règle encore utilisée par un résultat.
- **`NUMERIC(5, 2)`** pour les scores : nombre exact à 2 décimales, jusqu'à 999,99. Pas d'arrondi flottant du type 87,49999.
- **`UNIQUE (rule_code, version)`** : impossible d'avoir deux fois la version 3 de la même règle.
- **Index** (`CREATE INDEX`) : ils accélèrent les recherches fréquentes, par exemple les anomalies d'une analyse et d'une règle (`idx_anomaly_run_rule`), ou l'historique trié par date (`idx_analysis_run_started`). Sans eux, l'écran des anomalies relirait toute la table à chaque page.
- **`audit_log.user_login` en texte** : pas de clé étrangère vers `app_user`, pour que le journal d'audit reste intact même si un compte est supprimé (TRA-01).

**7.2 — `src/main/resources/db/dev/afterMigrate.sql`** : trois comptes de démonstration, un par rôle, chargés uniquement en profil `dev` (le dossier `db/dev` n'est déclaré que dans `application-dev.yml`). Mot de passe des trois : `Admin2026!`.

```sql
-- Comptes de démonstration, profil dev UNIQUEMENT (jamais chargé en prod).
-- Mot de passe des trois comptes : Admin2026!
-- Exécuté après chaque migration ; ON CONFLICT le rend rejouable sans erreur.
INSERT INTO app_user (login, full_name, email, password_hash, role)
VALUES ('admin',    'Administrateur (dev)', 'admin@dq.local',    '$2a$10$XPhL.KKvb1A6ymAWZf1CAe5ebUW.GgmlhxMECq9VXEnzWuJyu62r2', 'ADMIN'),
       ('analyste', 'Analyste (dev)',       'analyste@dq.local', '$2a$10$XPhL.KKvb1A6ymAWZf1CAe5ebUW.GgmlhxMECq9VXEnzWuJyu62r2', 'ANALYST'),
       ('lecteur',  'Lecteur (dev)',        'lecteur@dq.local',  '$2a$10$XPhL.KKvb1A6ymAWZf1CAe5ebUW.GgmlhxMECq9VXEnzWuJyu62r2', 'READER')
ON CONFLICT (login) DO NOTHING;
```

**Explication.**

- **`afterMigrate.sql`** : nom spécial reconnu par Flyway (un « callback ») : ce script s'exécute après chaque migration, à chaque démarrage. Il n'a pas de numéro de version, donc il n'apparaît pas dans l'historique des migrations et ne perturbe pas la prod.
- **`ON CONFLICT (login) DO NOTHING`** : si le compte existe déjà, PostgreSQL ne fait rien au lieu de lever une erreur. Le script peut donc tourner à chaque démarrage sans doublon.
- **La longue chaîne `$2a$10$...`** : ce n'est pas le mot de passe mais son empreinte BCrypt. `2a` est la variante de l'algorithme, `10` le coût (2¹⁰ tours de calcul), puis viennent un sel aléatoire et le résultat. On ne peut pas retrouver le mot de passe à partir de l'empreinte ; à la connexion, Spring recalcule l'empreinte de ce que l'utilisateur tape et compare. C'est ce qu'exige SEC-01.
- **Trois comptes, un par rôle** : pour tester tout de suite que le Lecteur ne peut pas modifier une règle (critère de recette), dès que les premiers écrans existeront.

Vérification : le nom du fichier est exactement `V1__init_schema.sql` (V majuscule, deux tirets bas). Le schéma a été contrôlé avec un analyseur syntaxique PostgreSQL ; il sera réellement appliqué au premier lancement (étape 11).

## Étape 8 — Code : sécurité, connexion et page d'accueil

Cette étape donne une application protégée : connexion par identifiant et mot de passe (USR-01), rôles (USR-02), mots de passe BCrypt (SEC-01), CSRF actif et politique CSP stricte. La double authentification s'ajoutera au lot MFA sans casser ce code. Tous les chemins Java partent de `src/main/java/ci/cnam/dqanalyzer/`.

La classe `DqAnalyzerApplication.java` générée ne change pas.

**8.1 — `shared/package-info.java`** : déclare `shared` comme module ouvert, utilisable par tous.

```java
/** Types transverses utilisables par tous les modules. Aucune logique métier ici. */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package ci.cnam.dqanalyzer.shared;

import org.springframework.modulith.ApplicationModule;
```

**Explication.** `package-info.java` est un fichier spécial qui décrit un package entier plutôt qu'une classe. L'annotation `@ApplicationModule(type = OPEN)` dit à Spring Modulith que `shared` est une boîte à outils commune : tous les modules peuvent utiliser toutes ses classes, y compris dans ses sous-dossiers. Sans elle, `rules` n'aurait pas le droit d'utiliser `shared/duckdb/DuckDbIdentifiers`.

**8.2 — `security/Role.java`**

```java
package ci.cnam.dqanalyzer.security;

/** Profils utilisateurs (CDC §4). Utilisés dans @PreAuthorize("hasRole('ANALYST')"), etc. */
public enum Role {
    ADMIN,
    ANALYST,
    READER
}
```

**Explication.** Un `enum` est une liste fermée de valeurs : le compilateur refuse `Role.SUPERVISEUR`, qui n'existe pas. On le place à la racine du module `security` parce que les autres modules en auront besoin (savoir si l'utilisateur est Analyste). Les noms sont identiques à la contrainte `CHECK` de la table `app_user`.

**8.3 — `security/domain/AppUser.java`** : l'entité JPA de la table `app_user`.

```java
package ci.cnam.dqanalyzer.security.domain;

import ci.cnam.dqanalyzer.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Compte utilisateur (table app_user). Les colonnes 2FA seront utilisées au lot « Double authentification ». */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String login;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AppUser() {
        // requis par JPA
    }

    public AppUser(String login, String fullName, String email, String passwordHash, Role role) {
        this.login = login;
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public String getLogin() {
        return login;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }
}
```

**Explication.**

- **`@Entity` / `@Table`** : indique à JPA (Hibernate) que cette classe représente une ligne de la table `app_user`. Hibernate écrit le SQL de lecture et d'écriture à ta place.
- **`@Id` + `IDENTITY`** : la clé primaire est générée par PostgreSQL (la colonne `GENERATED ALWAYS AS IDENTITY` de l'étape 7).
- **`@Column(name = "full_name")`** : relie le champ Java `fullName` à la colonne SQL `full_name`. `nullable` et `length` doivent correspondre au SQL : au démarrage, `ddl-auto: validate` compare les deux.
- **`@Enumerated(EnumType.STRING)`** : enregistre le rôle en texte (`ADMIN`) et non en numéro (0, 1, 2). Ajouter un rôle au milieu de l'`enum` ne fausse donc pas les comptes existants.
- **Constructeur `protected` vide** : Hibernate en a besoin pour recréer l'objet quand il lit la base ; `protected` empêche le reste du code de créer un utilisateur incomplet.
- **Pas de `setPassword`** : les champs ne changent que par des méthodes métier qui viendront avec les écrans d'administration. On évite ainsi qu'un mot de passe soit modifié sans être haché.
- **`isLockedAt`** : le compte est bloqué si `locked_until` est dans le futur ; servira au blocage après 5 codes 2FA erronés (MFA-06).

**8.4 — `security/infra/AppUserRepository.java`**

```java
package ci.cnam.dqanalyzer.security.infra;

import ci.cnam.dqanalyzer.security.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByLoginIgnoreCase(String login);
}
```

**Explication.** Tu écris seulement l'interface ; Spring Data génère l'implémentation au démarrage. `JpaRepository` fournit déjà `save`, `findById`, `findAll`, `delete`. La méthode `findByLoginIgnoreCase` est traduite automatiquement à partir de son nom en `SELECT … WHERE lower(login) = lower(?)` : « admin » et « Admin » désignent le même compte. `Optional` signifie « peut ne rien trouver » et oblige l'appelant à traiter ce cas.

**8.5 — `security/infra/DatabaseUserDetailsService.java`** : fait le lien entre la table et Spring Security.

```java
package ci.cnam.dqanalyzer.security.infra;

import java.time.Instant;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Charge un utilisateur depuis app_user pour Spring Security (USR-01, USR-02). */
@Service
class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) {
        var user = users.findByLoginIgnoreCase(login)
                .orElseThrow(() -> new UsernameNotFoundException("Identifiant ou mot de passe incorrect"));
        return User.withUsername(user.getLogin())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .disabled(!user.isActive())
                .accountLocked(user.isLockedAt(Instant.now()))
                .build();
    }
}
```

**Explication.** Spring Security ne sait pas où sont tes utilisateurs. Il appelle `loadUserByUsername` avec l'identifiant tapé, et attend un `UserDetails` : identifiant, empreinte du mot de passe, rôles, compte actif ou non, bloqué ou non. Il compare ensuite lui-même le mot de passe tapé avec l'empreinte.

- **`.roles(...)`** : Spring ajoute le préfixe `ROLE_` (`ROLE_ADMIN`) ; c'est ce que vérifie `hasRole('ADMIN')`.
- **Même message si le compte n'existe pas** : « Identifiant ou mot de passe incorrect » dans tous les cas, pour qu'un attaquant ne puisse pas deviner quels identifiants existent.
- **`@Transactional(readOnly = true)`** : la lecture se fait dans une transaction en lecture seule, plus légère pour la base.
- **Classe sans `public`** : visible seulement dans son package. Personne d'autre n'a à l'appeler directement ; Spring la trouve quand même grâce à `@Service`.

**8.6 — `security/infra/SecurityConfig.java`**

```java
package ci.cnam.dqanalyzer.security.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Sécurité web : tout est protégé sauf la page de connexion, les ressources statiques et le point de santé.
 * Les droits fins se déclarent sur chaque contrôleur avec @PreAuthorize (SEC-02).
 * La double authentification (MFA-*) s'ajoutera ici au lot dédié.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/login", "/webjars/**", "/css/**", "/js/**", "/images/**", "/error")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/actuator/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'")));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

**Explication bloc par bloc.**

- **`@Configuration` + `@Bean`** : la classe fournit à Spring deux objets prêts à l'emploi : la chaîne de filtres de sécurité et l'encodeur de mots de passe.
- **`@EnableMethodSecurity`** : active `@PreAuthorize("hasRole('ANALYST')")` sur les méthodes des contrôleurs, pour vérifier les droits à chaque écran et chaque API (SEC-02).
- **`authorizeHttpRequests`** : les règles sont lues dans l'ordre, la première qui correspond s'applique. Libres d'accès : la page de connexion, les styles, scripts et images (sinon la page de connexion s'afficherait sans style), la page d'erreur et le point de santé (pour la supervision). Le reste de l'Actuator est réservé aux administrateurs. Tout le reste exige d'être connecté (`anyRequest().authenticated()`) : une nouvelle page est protégée par défaut, même si on oublie d'y penser.
- **`formLogin`** : utilise notre page `/login` ; après connexion, renvoie toujours vers l'accueil.
- **`logout`** : la déconnexion se fait par un formulaire POST (bouton de la barre de navigation), puis renvoie vers `/login?logout`.
- **CSRF** : actif par défaut, rien à écrire. Chaque formulaire contient un jeton secret que Thymeleaf ajoute tout seul ; un site malveillant ne peut pas soumettre un formulaire à la place de l'utilisateur (SEC-03).
- **`contentSecurityPolicy`** : en-tête envoyé au navigateur qui n'autorise que les styles et scripts venant de l'application elle-même. Même si une valeur malveillante d'un fichier importé s'affichait dans une page, le navigateur refuserait d'exécuter un script injecté.
- **`BCryptPasswordEncoder(12)`** : coût 12, soit 2¹² tours de calcul, environ 0,2 à 0,3 seconde par vérification. Imperceptible pour l'utilisateur, mais cela rend une attaque par essais massifs très lente. Les empreintes de coût 10 du jeu de démonstration restent lisibles : le coût est inscrit dans chaque empreinte.

**8.7 — `security/api/LoginController.java`**

```java
package ci.cnam.dqanalyzer.security.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class LoginController {

    @GetMapping("/login")
    String login() {
        return "security/login";
    }
}
```

**Explication.** `@Controller` déclare une classe qui reçoit des requêtes web. `@GetMapping("/login")` associe l'adresse `/login` à la méthode. La valeur renvoyée, `"security/login"`, est le nom du template à afficher : `templates/security/login.html`. Le traitement du formulaire (vérification du mot de passe) n'est pas ici : Spring Security s'en charge.

**8.8 — `shared/web/HomeController.java`**

```java
package ci.cnam.dqanalyzer.shared.web;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Page d'accueil : point de départ des parcours (ERG-01 : toute action courante à 3 clics au plus). */
@Controller
class HomeController {

    @GetMapping("/")
    String home(Principal principal, Model model) {
        model.addAttribute("username", principal.getName());
        return "home";
    }
}
```

**Explication.** `Principal` est l'utilisateur connecté, fourni automatiquement par Spring Security. `Model` est le sac de données transmis à la page : `username` y est déposé, puis lu dans `home.html` par `${username}`. La page d'accueil est dans `shared` car elle n'appartient à aucun module métier ; elle accueillera les raccourcis vers les parcours.

**8.9 — `src/main/resources/templates/fragments/layout.html`** : éléments communs à toutes les pages.

```html
<!DOCTYPE html>
<html lang="fr" xmlns:th="http://www.thymeleaf.org">
<!-- Fragments communs. Utilisation : th:replace="~{fragments/layout :: head(#{home.title})}" -->
<head th:fragment="head(title)">
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="htmx-config" content='{"includeIndicatorStyles": false}'>
  <title th:text="${title} + ' · ' + #{app.name}">DQ-Analyzer</title>
  <link rel="stylesheet" th:href="@{/webjars/bootstrap/css/bootstrap.min.css}">
  <link rel="stylesheet" th:href="@{/css/app.css}">
  <script th:src="@{/webjars/htmx.org/dist/htmx.min.js}" defer></script>
</head>
<body>

<nav th:fragment="navbar" class="navbar navbar-expand navbar-dark bg-primary mb-4">
  <div class="container">
    <a class="navbar-brand fw-semibold" th:href="@{/}" th:text="#{app.name}">DQ-Analyzer</a>
    <ul class="navbar-nav me-auto">
      <li class="nav-item"><a class="nav-link" th:href="@{/}" th:text="#{nav.home}">Accueil</a></li>
    </ul>
    <form th:action="@{/logout}" method="post" class="d-flex">
      <button type="submit" class="btn btn-outline-light btn-sm" th:text="#{nav.logout}">Se déconnecter</button>
    </form>
  </div>
</nav>

<footer th:fragment="footer" class="container text-muted small py-4" th:text="#{app.tagline}"></footer>

</body>
</html>
```

**Explication.** Un fragment est un morceau de page réutilisable. `th:fragment="head(title)"` définit l'en-tête commun, avec un paramètre pour le titre de l'onglet ; chaque page l'importe au lieu de recopier les liens CSS et JS. Si tu changes Bootstrap ou ajoutes un script, tu le fais ici, une fois.

- **`th:text="#{app.name}"`** : affiche le texte de `messages.properties`. Le texte entre les balises (`DQ-Analyzer`) n'est qu'un exemple visible si on ouvre le fichier directement dans un navigateur.
- **`th:href="@{/webjars/...}"`** : construit le lien en tenant compte de l'adresse de l'application sur le serveur.
- **`defer`** : HTMX se charge après l'affichage de la page, qui apparaît plus vite.
- **`htmx-config includeIndicatorStyles: false`** : HTMX ajoute sinon un petit style en ligne que la politique CSP bloquerait, avec une erreur dans la console.
- **Formulaire de déconnexion en POST** : une déconnexion par simple lien (GET) pourrait être déclenchée par une image piégée sur un autre site.

**8.10 — `src/main/resources/templates/security/login.html`**

```html
<!DOCTYPE html>
<html lang="fr" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head(#{login.title})}"></head>
<body class="bg-light">
<main class="container login-box">
  <h1 class="h4 mb-1" th:text="#{app.name}">DQ-Analyzer</h1>
  <p class="text-muted small mb-4" th:text="#{app.tagline}"></p>

  <div th:if="${param.error}" class="alert alert-danger" role="alert" th:text="#{login.error}"></div>
  <div th:if="${param.logout}" class="alert alert-info" role="status" th:text="#{login.logout}"></div>

  <!-- th:action ajoute automatiquement le jeton CSRF -->
  <form th:action="@{/login}" method="post" class="card card-body shadow-sm">
    <div class="mb-3">
      <label for="username" class="form-label" th:text="#{login.username}">Identifiant</label>
      <input id="username" name="username" class="form-control" autocomplete="username" required autofocus>
    </div>
    <div class="mb-3">
      <label for="password" class="form-label" th:text="#{login.password}">Mot de passe</label>
      <input id="password" name="password" type="password" class="form-control" autocomplete="current-password" required>
    </div>
    <button type="submit" class="btn btn-primary w-100" th:text="#{login.submit}">Se connecter</button>
  </form>
</main>
</body>
</html>
```

**Explication.** `th:replace` remplace la balise par le fragment commun. `${param.error}` vaut vrai quand l'adresse contient `?error` : Spring Security redirige vers `/login?error` après un échec, et la page affiche le message rouge. Les champs doivent s'appeler `username` et `password` : ce sont les noms attendus par Spring Security. `autocomplete` permet au gestionnaire de mots de passe du navigateur de remplir les champs correctement.

**8.11 — `src/main/resources/templates/home.html`**

```html
<!DOCTYPE html>
<html lang="fr" xmlns:th="http://www.thymeleaf.org">
<head th:replace="~{fragments/layout :: head(#{home.title})}"></head>
<body>
<nav th:replace="~{fragments/layout :: navbar}"></nav>
<main class="container">
  <h1 class="h3" th:text="#{home.welcome(${username})}">Bienvenue</h1>
  <p class="lead" th:text="#{home.intro}"></p>
  <div class="alert alert-secondary" th:text="#{home.soon}"></div>
</main>
<footer th:replace="~{fragments/layout :: footer}"></footer>
</body>
</html>
```

**Explication.** La page assemble les trois fragments (en-tête, barre de navigation, pied de page) et ne contient que son propre contenu. `#{home.welcome(${username})}` remplace le `{0}` du message par le nom de l'utilisateur.

**8.12 — `src/main/resources/static/css/app.css`**

```css
/* Styles propres à DQ-Analyzer. Pas de style en ligne dans les templates (politique CSP). */
.login-box {
  max-width: 420px;
  padding-top: 10vh;
}
```

**Explication.** Tout ce qui est dans `src/main/resources/static/` est servi tel quel : ce fichier est disponible à l'adresse `/css/app.css`. Il ne contient que ce que Bootstrap ne fait pas ; ici, centrer et limiter la largeur du bloc de connexion.

Pourquoi pas d'attribut `style="…"` ni de `<script>` dans les pages : la politique CSP de `SecurityConfig` les bloque, ce qui ferme la porte aux injections de script. Tout style va dans `app.css`, tout script dans un fichier `.js` de `static/js`.

Vérification : `./mvnw -q compile` se termine sans erreur.

## Étape 9 — Code : moteur de règles, CMP-01 et calcul des scores

C'est le cœur du projet. Le principe : un fichier importé devient une table DuckDB ; chaque type de règle est une classe qui traduit ses paramètres en SQL ; le moteur compte les lignes conformes et envoie les anomalies au fil de l'eau, sans jamais tout garder en mémoire. Le calcul des scores (CDC §7) vit dans une seule classe, `Scoring`.

Pour aller vite sur les 22 règles restantes, la classe `RowPredicateEvaluator` fait tout le travail commun : une règle ligne à ligne n'a plus qu'à fournir la condition SQL qui repère une anomalie (CMP-01 tient en 10 lignes utiles).

**Explication du parcours d'une analyse**, pour situer chaque classe de cette étape :

1. L'import (lot suivant) charge le fichier dans une table DuckDB : une colonne `_row_num` (n° de ligne d'origine) puis une colonne texte par colonne du fichier. Cette table est le `Dataset`.
2. Pour chaque règle du modèle, le moteur prend la version exacte (`RuleDefinition`) et demande au `RuleEvaluatorRegistry` l'évaluateur de son type (`CMP-01` → `Cmp01RequiredValueEvaluator`).
3. L'évaluateur exécute deux requêtes SQL : une qui compte les lignes contrôlées et en anomalie, une qui liste les anomalies. Il envoie chaque anomalie au `AnomalySink` et renvoie les comptages (`RuleResult`).
4. `Scoring` transforme les comptages en scores et statuts.

**Pourquoi DuckDB plutôt que Java pur** : DuckDB est une base de données qui tourne dans l'application, sans serveur, et range les données par colonne sur le disque. Compter les cellules vides d'une colonne de 10 millions de lignes y prend une fraction de seconde, sans charger le fichier en mémoire Java (PERF-03).

**Pourquoi tout est en anglais dans le code** : c'est la règle du `CLAUDE.md` et la norme des équipes de développement ; les textes que voit l'utilisateur restent en français.

### 9.1 — Outils SQL sûrs (`shared/duckdb/`)

`DuckDbIdentifiers.java` : seule façon autorisée d'insérer un nom de colonne dans du SQL.

```java
package ci.cnam.dqanalyzer.shared.duckdb;

import java.util.Objects;

/**
 * Seule façon autorisée d'insérer un nom de colonne ou de table dans une requête DuckDB.
 * Les valeurs, elles, passent toujours par des paramètres "?".
 */
public final class DuckDbIdentifiers {

    private DuckDbIdentifiers() {}

    /** Entoure l'identifiant de guillemets doubles et double les guillemets internes. */
    public static String quote(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("Identifiant vide");
        }
        if (identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Identifiant invalide");
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
```

**Explication.** Le danger principal du moteur est l'injection SQL : un nom de colonne venant d'un fichier importé, comme `x" OR 1=1 --`, pourrait modifier la requête. En SQL, un nom placé entre guillemets doubles est toujours lu comme un nom, jamais comme du code ; un guillemet à l'intérieur du nom doit être doublé. `quote` fait exactement cela, et refuse les noms vides ou contenant le caractère nul. Les valeurs (un seuil, une liste autorisée) ne passent jamais par là : elles vont dans des paramètres `?`, que le pilote transmet séparément de la requête.

`SqlFragment.java` : un morceau de SQL et ses paramètres, dans l'ordre des `?`.

```java
package ci.cnam.dqanalyzer.shared.duckdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Morceau de SQL et ses paramètres positionnels, dans l'ordre des "?".
 * Le texte ne doit contenir que des mots-clés, des identifiants passés par {@link DuckDbIdentifiers#quote}
 * et des "?" : jamais une valeur saisie par l'utilisateur.
 */
public record SqlFragment(String sql, List<Object> params) {

    public static final SqlFragment TRUE = new SqlFragment("TRUE", List.of());

    public SqlFragment {
        Objects.requireNonNull(sql, "sql");
        params = List.copyOf(params);
    }

    public static SqlFragment of(String sql, Object... params) {
        return new SqlFragment(sql, List.of(params));
    }

    public SqlFragment and(SqlFragment other) {
        var all = new ArrayList<>(params);
        all.addAll(other.params);
        return new SqlFragment("(" + sql + ") AND (" + other.sql + ")", all);
    }
}
```

**Explication.** Un `record` est une classe de données immuable en une ligne : Java génère le constructeur, les accesseurs (`sql()`, `params()`), `equals` et `toString`. `SqlFragment` transporte ensemble un morceau de condition et ses valeurs, pour qu'ils restent toujours dans le même ordre. `and()` combine deux conditions en ajoutant des parenthèses, indispensables quand une condition contient un `OR` (c'est le cas de CMP-01). `List.copyOf` rend la liste non modifiable.

### 9.2 — Contrat du moteur (`rules/`, racine du module = visible des autres modules)

`Dimension.java`

```java
package ci.cnam.dqanalyzer.rules;

/** Dimensions de qualité V1 (CDC §2). Libellés français dans messages.properties : dimension.<NOM>. */
public enum Dimension {
    COMPLETENESS,
    UNIQUENESS,
    VALIDITY,
    ACCURACY,
    CONSISTENCY,
    TIMELINESS,
    REFERENTIAL_INTEGRITY
}
```

`Severity.java`

```java
package ci.cnam.dqanalyzer.rules;

/** Sévérité d'une règle et son poids par défaut dans le score de dimension (CDC §7). */
public enum Severity {
    BLOCKING(3),
    MAJOR(2),
    MINOR(1);

    private final int defaultWeight;

    Severity(int defaultWeight) {
        this.defaultWeight = defaultWeight;
    }

    public int defaultWeight() {
        return defaultWeight;
    }
}
```

**Explication de `Dimension` et `Severity`.** Ce sont les deux classements du CDC (§2 et §6), sous forme de listes fermées. Le poids de chaque sévérité est rangé dans l'`enum` lui-même (`BLOCKING(3)`), pour que la valeur par défaut du CDC §7 soit écrite à un seul endroit ; l'administrateur pourra la remplacer (voir `Scoring`).

`Normalization.java` : options de normalisation avant contrôle (CDC §6).

```java
package ci.cnam.dqanalyzer.rules;

/**
 * Normalisation appliquée à la valeur avant contrôle (CDC §6). La valeur affichée dans les anomalies
 * reste toujours la valeur d'origine.
 */
public record Normalization(boolean trim, boolean upperCase, boolean stripAccents) {

    public static final Normalization NONE = new Normalization(false, false, false);

    /** Applique la normalisation à une expression SQL DuckDB déjà sûre (identifiant cité). */
    public String apply(String sqlExpression) {
        String expr = "CAST(" + sqlExpression + " AS VARCHAR)";
        if (trim) {
            expr = "trim(" + expr + ")";
        }
        if (stripAccents) {
            expr = "strip_accents(" + expr + ")";
        }
        if (upperCase) {
            expr = "upper(" + expr + ")";
        }
        return expr;
    }
}
```

**Explication.** Le CDC demande de pouvoir comparer « KOUAMÉ Jean » et « kouame jean » comme identiques (UNI-03). `apply` entoure l'expression SQL des fonctions DuckDB voulues : `trim` retire les espaces au début et à la fin, `strip_accents` enlève les accents, `upper` met en majuscules. Exemple avec les trois options : `upper(strip_accents(trim(CAST("nom" AS VARCHAR))))`. La comparaison porte sur la valeur normalisée, mais l'anomalie affiche toujours la valeur d'origine du fichier.

`RuleDefinition.java` : une version précise d'une règle, telle qu'elle est exécutée.

```java
package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Une version précise d'une règle, telle qu'elle est exécutée. Objet immuable, indépendant de JPA.
 *
 * @param thresholdPercent seuil d'acceptation entre 0 et 100
 * @param params paramètres propres au type de règle (stockés en JSON)
 */
public record RuleDefinition(
        Long id,
        String ruleCode,
        int version,
        String name,
        String type,
        Dimension dimension,
        List<String> targetColumns,
        Map<String, Object> params,
        Normalization normalization,
        Severity severity,
        double thresholdPercent,
        String message) {

    public RuleDefinition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(severity, "severity");
        targetColumns = List.copyOf(targetColumns);
        params = params == null ? Map.of() : Map.copyOf(params);
        normalization = normalization == null ? Normalization.NONE : normalization;
        if (thresholdPercent < 0 || thresholdPercent > 100) {
            throw new IllegalArgumentException("Le seuil doit être compris entre 0 et 100");
        }
    }

    /** Première colonne ciblée : la plupart des règles n'en ont qu'une. */
    public String firstColumn() {
        if (targetColumns.isEmpty()) {
            throw new RuleEvaluationException("La règle " + ruleCode + " ne cible aucune colonne");
        }
        return targetColumns.getFirst();
    }
}
```

**Explication.** C'est la règle telle qu'elle est exécutée, indépendante de la base de données : le moteur ne dépend pas de JPA et se teste sans PostgreSQL. Le bloc `public RuleDefinition { … }` (constructeur compact) vérifie les données à la création : type, dimension et sévérité obligatoires, seuil entre 0 et 100, listes rendues immuables. Une règle mal formée est donc refusée immédiatement, avec un message clair, au lieu de provoquer une erreur obscure au milieu d'une analyse. `ruleCode` + `version` identifient la version exacte, ce qui permet de retrouver plus tard les règles utilisées par une analyse (TRA-02).

`RuleEvaluationException.java`

```java
package ci.cnam.dqanalyzer.rules;

/** Erreur de paramétrage ou d'exécution d'une règle. Le message doit être lisible par l'analyste. */
public class RuleEvaluationException extends RuntimeException {

    public RuleEvaluationException(String message) {
        super(message);
    }

    public RuleEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

**Explication.** Une exception propre au moteur, distincte des erreurs techniques : quand l'écran la reçoit, il sait que son message est destiné à l'analyste (« La colonne « telephone » n'existe pas dans le fichier ») et peut l'afficher tel quel (ERG-02).

`RuleResult.java` : uniquement des comptages ; le score est calculé par `Scoring`.

```java
package ci.cnam.dqanalyzer.rules;

/**
 * Comptages bruts produits par un évaluateur. Le score et le statut sont calculés ailleurs (Scoring).
 *
 * @param checkedRows lignes concernées par la règle (hors lignes exclues par une condition)
 * @param conformingRows lignes conformes parmi les lignes contrôlées
 * @param duplicateGroups nombre de groupes de doublons, renseigné uniquement pour les règles d'unicité
 */
public record RuleResult(long checkedRows, long conformingRows, Long duplicateGroups) {

    public RuleResult {
        if (checkedRows < 0 || conformingRows < 0 || conformingRows > checkedRows) {
            throw new IllegalArgumentException(
                    "Comptages incohérents : contrôlées=" + checkedRows + ", conformes=" + conformingRows);
        }
    }

    public static RuleResult of(long checkedRows, long conformingRows) {
        return new RuleResult(checkedRows, conformingRows, null);
    }

    public long anomalyRows() {
        return checkedRows - conformingRows;
    }
}
```

**Explication.** L'évaluateur ne calcule pas de pourcentage : il compte. Séparer comptage et calcul garantit que le score est calculé de la même façon pour toutes les règles, par une seule classe. Le constructeur refuse les comptages impossibles (plus de lignes conformes que de lignes contrôlées), qui signaleraient un bug dans une règle.

`Anomaly.java`

```java
package ci.cnam.dqanalyzer.rules;

/**
 * Une cellule en anomalie.
 *
 * @param rowNum numéro de ligne dans le fichier d'origine (tel que l'analyste le voit dans Excel)
 * @param value valeur d'origine, non normalisée
 * @param duplicateGroup identifiant du groupe de doublons, ou null hors règles d'unicité
 */
public record Anomaly(long rowNum, String column, String value, String message, Long duplicateGroup) {}
```

`AnomalySink.java`

```java
package ci.cnam.dqanalyzer.rules;

/**
 * Reçoit les anomalies une par une, au fil de la lecture : on ne les garde jamais toutes en mémoire.
 * L'implémentation de production les écrit par lots dans la table anomaly.
 */
@FunctionalInterface
public interface AnomalySink {

    void accept(Anomaly anomaly);

    AnomalySink DISCARD = anomaly -> {};
}
```

**Explication de `Anomaly` et `AnomalySink`.** Une anomalie porte exactement ce que demande RAP-03 : n° de ligne, colonne, valeur, message, et le groupe de doublons pour RAP-04. Un fichier de 2 Go peut produire des millions d'anomalies : les ranger toutes dans une liste ferait déborder la mémoire. Le « sink » (récepteur) les reçoit une par une ; en production, il les écrira par paquets de quelques milliers dans la table `anomaly`. Dans les tests, on lui passe simplement `anomalies::add` pour les collecter dans une liste.

`Dataset.java` : le fichier importé, vu comme une table DuckDB.

```java
package ci.cnam.dqanalyzer.rules;

import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * Fichier importé, chargé dans une table DuckDB. La table contient la colonne technique
 * {@value #ROW_NUM_COLUMN} (n° de ligne d'origine) puis les colonnes du fichier, toutes en VARCHAR.
 */
public record Dataset(Connection connection, String tableName, List<String> columns) {

    public static final String ROW_NUM_COLUMN = "_row_num";

    public Dataset {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tableName, "tableName");
        columns = List.copyOf(columns);
    }

    /** Nom de table cité, prêt à être inséré dans du SQL. */
    public String quotedTable() {
        return DuckDbIdentifiers.quote(tableName);
    }

    /** Vérifie que la colonne existe dans le fichier, puis la renvoie citée. */
    public String quotedColumn(String column) {
        if (!columns.contains(column)) {
            throw new RuleEvaluationException("La colonne « " + column + " » n'existe pas dans le fichier");
        }
        return DuckDbIdentifiers.quote(column);
    }
}
```

**Explication.** Le `Dataset` connaît la connexion DuckDB, le nom de la table et la liste des colonnes du fichier. `quotedColumn` fait deux contrôles avant d'insérer un nom de colonne dans du SQL : il vérifie que la colonne existe vraiment dans le fichier, puis la met entre guillemets. Toutes les colonnes sont stockées en texte (`VARCHAR`) : on garde la valeur exacte du fichier (un `007` reste `007`), et c'est la règle VAL-01 qui contrôle si une valeur est bien un nombre ou une date.

`ReferenceProvider.java`

```java
package ci.cnam.dqanalyzer.rules;

/**
 * Donne accès aux tables de référence (EXA-01, INT-01). Implémenté plus tard par le module reference,
 * qui les charge dans la même base DuckDB que le fichier analysé.
 */
public interface ReferenceProvider {

    /** Nom de la table DuckDB contenant la version demandée de la table de référence. */
    String duckDbTable(long referenceTableId);

    ReferenceProvider NONE = id -> {
        throw new RuleEvaluationException("Aucune table de référence n'est disponible pour cette analyse");
    };
}
```

**Explication.** Les règles EXA-01 et INT-01 comparent le fichier à une table de référence (le référentiel des communes, par exemple). Cette interface dit seulement « donne-moi la table DuckDB de cette référence » ; le module `reference` l'implémentera. Le moteur ne dépend donc pas de la façon dont les références sont stockées. `NONE` sert quand une analyse n'utilise aucune référence : une règle qui en demande une reçoit alors un message clair.

`ParamSchema.java` : décrit les paramètres d'un type de règle, pour valider et générer le formulaire (REG-02).

```java
package ci.cnam.dqanalyzer.rules;

import java.util.List;

/**
 * Description des paramètres attendus par un type de règle. Sert à valider une règle et à générer
 * son formulaire (REG-02).
 */
public record ParamSchema(ColumnArity columns, List<Param> params) {

    public ParamSchema {
        params = List.copyOf(params);
    }

    public static ParamSchema singleColumn(Param... params) {
        return new ParamSchema(ColumnArity.ONE, List.of(params));
    }

    public enum ColumnArity {
        ONE,
        TWO,
        MANY
    }

    public enum ParamType {
        TEXT,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        TEXT_LIST,
        COLUMN,
        REFERENCE_TABLE,
        EXPRESSION
    }

    /** @param labelKey clé du libellé français dans messages.properties */
    public record Param(String name, ParamType type, boolean required, String labelKey) {}
}
```

**Explication.** Chaque type de règle décrit ses paramètres : combien de colonnes (une, deux, plusieurs), et quels réglages (nom, type, obligatoire ou non, libellé). L'écran de création de règle lira ce schéma pour afficher seulement les champs utiles (REG-02) : choisir VAL-04 affichera « minimum » et « maximum », choisir CMP-01 seulement la colonne. Ajouter un type de règle ajoute donc son formulaire sans écrire de HTML.

`RuleEvaluator.java` : l'interface du CDC §9.3, avec en plus le récepteur d'anomalies.

```java
package ci.cnam.dqanalyzer.rules;

/**
 * Un type de règle du catalogue (CDC §6). Ajouter un type = ajouter une classe {@code @Component}
 * qui implémente cette interface, sans toucher au moteur (MAI-01).
 */
public interface RuleEvaluator {

    /** Code du catalogue, par ex. "CMP-01". */
    String type();

    Dimension dimension();

    ParamSchema paramSchema();

    /**
     * Évalue la règle sur le jeu de données. Les anomalies sont envoyées au fil de l'eau dans le sink ;
     * le résultat ne contient que des comptages.
     */
    RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink);
}
```

**Explication.** C'est le contrat que toute règle doit respecter (patron « Stratégie ») : le moteur appelle `evaluate` sans savoir de quelle règle il s'agit. Par rapport à l'interface du CDC §9.3, j'ai ajouté le paramètre `AnomalySink`, pour transmettre les anomalies sans les garder en mémoire.

`RuleEvaluatorRegistry.java` : Spring y injecte automatiquement tous les évaluateurs.

```java
package ci.cnam.dqanalyzer.rules;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Catalogue des types de règles disponibles, découverts automatiquement par Spring. */
@Component
public class RuleEvaluatorRegistry {

    private final Map<String, RuleEvaluator> evaluators;

    public RuleEvaluatorRegistry(List<RuleEvaluator> evaluators) {
        this.evaluators = evaluators.stream()
                .collect(Collectors.toUnmodifiableMap(RuleEvaluator::type, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Deux évaluateurs déclarent le type " + a.type() + " : "
                            + a.getClass().getName() + " et " + b.getClass().getName());
                }));
    }

    public RuleEvaluator get(String type) {
        var evaluator = evaluators.get(type);
        if (evaluator == null) {
            throw new RuleEvaluationException("Type de règle inconnu : " + type);
        }
        return evaluator;
    }

    public SortedSet<String> types() {
        return new TreeSet<>(evaluators.keySet());
    }
}
```

**Explication.** Au démarrage, Spring cherche toutes les classes annotées `@Component` qui implémentent `RuleEvaluator` et les passe en liste au constructeur. Le registre les range dans une table « code → évaluateur ». C'est ce qui réalise MAI-01 : pour ajouter UNI-01, tu crées une classe `@Component`, et elle apparaît dans le catalogue sans toucher au moteur. Si deux classes déclarent le même code, l'application refuse de démarrer, ce qui évite qu'une règle en masque une autre sans qu'on le voie.

### 9.3 — Évaluateurs (`rules/evaluators/`, interne au module)

`RowPredicateEvaluator.java` : base commune des règles ligne à ligne.

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.AnomalySink;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.rules.RuleEvaluator;
import ci.cnam.dqanalyzer.rules.RuleResult;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * Base des règles ligne à ligne (complétude, validité, cohérence…). Une sous-classe ne fournit que
 * la condition SQL qui identifie une ligne en anomalie ; le comptage et l'extraction sont faits ici.
 */
public abstract class RowPredicateEvaluator implements RuleEvaluator {

    private static final int FETCH_SIZE = 5_000;

    /** Condition vraie quand la ligne est en anomalie. */
    protected abstract SqlFragment violation(Dataset data, RuleDefinition rule, ReferenceProvider refs);

    /** Lignes concernées par la règle. Par défaut toutes ; à surcharger pour les règles conditionnelles. */
    protected SqlFragment scope(Dataset data, RuleDefinition rule) {
        return SqlFragment.TRUE;
    }

    /** Colonne dont la valeur est reportée dans l'anomalie. */
    protected String anomalyColumn(RuleDefinition rule) {
        return rule.firstColumn();
    }

    /** Message par défaut, lisible par un non-informaticien (ERG-02), si la règle n'en définit pas. */
    protected abstract String defaultMessage(RuleDefinition rule);

    @Override
    public final RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
        var violation = violation(data, rule, refs);
        var scope = scope(data, rule);
        String table = data.quotedTable();
        String column = anomalyColumn(rule);
        String quotedColumn = data.quotedColumn(column);
        String message = rule.message() == null || rule.message().isBlank() ? defaultMessage(rule) : rule.message();

        try {
            long checked;
            long failed;
            String countSql = "SELECT count(*), count(*) FILTER (WHERE " + violation.sql() + ") FROM " + table
                    + " WHERE " + scope.sql();
            try (PreparedStatement ps = data.connection().prepareStatement(countSql)) {
                bind(ps, violation.params(), scope.params());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    checked = rs.getLong(1);
                    failed = rs.getLong(2);
                }
            }

            var filter = scope.and(violation);
            String anomaliesSql = "SELECT " + Dataset.ROW_NUM_COLUMN + ", CAST(" + quotedColumn + " AS VARCHAR) FROM "
                    + table + " WHERE " + filter.sql() + " ORDER BY " + Dataset.ROW_NUM_COLUMN;
            try (PreparedStatement ps = data.connection().prepareStatement(anomaliesSql)) {
                ps.setFetchSize(FETCH_SIZE);
                bind(ps, filter.params());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sink.accept(new Anomaly(rs.getLong(1), column, rs.getString(2), message, null));
                    }
                }
            }
            return RuleResult.of(checked, checked - failed);
        } catch (SQLException e) {
            throw new RuleEvaluationException("Échec de la règle " + rule.ruleCode() + " (" + type() + ")", e);
        }
    }

    @SafeVarargs
    private static void bind(PreparedStatement ps, List<Object>... paramLists) throws SQLException {
        int index = 1;
        for (var params : paramLists) {
            for (Object param : params) {
                ps.setObject(index++, param);
            }
        }
    }
}
```

**Explication.** C'est la classe qui fera gagner le plus de temps : 18 des 23 règles du catalogue sont des règles ligne à ligne (toutes sauf CMP-04 et UNI-01 à UNI-04, qui portent sur l'ensemble du fichier). Une sous-classe fournit seulement :

- `violation` : la condition SQL vraie quand une ligne est en anomalie ;
- `scope` (facultatif) : les lignes concernées, pour les règles conditionnelles comme CMP-03 (« si statut = Ayant droit ») ;
- `defaultMessage` : le message affiché si l'analyste n'en a pas écrit.

`evaluate` fait le reste. Exemple, pour CMP-01 sur la colonne `num_secu`, il exécute :

```sql
-- 1. Comptage : lignes contrôlées et lignes en anomalie, en un seul passage
SELECT count(*), count(*) FILTER (WHERE "num_secu" IS NULL OR trim(CAST("num_secu" AS VARCHAR)) = '')
FROM "ds_test" WHERE TRUE;

-- 2. Liste des anomalies, triée par n° de ligne
SELECT _row_num, CAST("num_secu" AS VARCHAR) FROM "ds_test"
WHERE (TRUE) AND ("num_secu" IS NULL OR trim(CAST("num_secu" AS VARCHAR)) = '')
ORDER BY _row_num;
```

- **`count(*) FILTER (WHERE …)`** : compte seulement les lignes qui vérifient la condition, dans la même lecture que le total.
- **`setFetchSize(5_000)`** : les anomalies sont lues par paquets de 5 000, jamais toutes d'un coup.
- **`final`** : une sous-classe ne peut pas réécrire `evaluate`, donc ne peut pas oublier une étape ni mal calculer les comptages.
- **`try (...)`** : ferme automatiquement les requêtes et résultats, même en cas d'erreur, pour ne pas laisser de ressources ouvertes.
- **`bind`** : remplit les `?` dans le bon ordre (d'abord les valeurs de la condition d'anomalie, puis celles du périmètre, comme dans la requête).

`Cmp01RequiredValueEvaluator.java` : la première règle du catalogue.

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ParamSchema;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.shared.duckdb.SqlFragment;
import org.springframework.stereotype.Component;

/** CMP-01 — Champ obligatoire non vide. Une valeur absente ou composée uniquement d'espaces est en anomalie. */
@Component
public class Cmp01RequiredValueEvaluator extends RowPredicateEvaluator {

    @Override
    public String type() {
        return "CMP-01";
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
        String column = data.quotedColumn(rule.firstColumn());
        return SqlFragment.of(column + " IS NULL OR trim(CAST(" + column + " AS VARCHAR)) = ''");
    }

    @Override
    protected String defaultMessage(RuleDefinition rule) {
        return "La colonne « " + rule.firstColumn() + " » doit être renseignée.";
    }
}
```

**Explication.** La règle tient en une condition : la valeur est absente (`IS NULL`) ou ne contient que des espaces une fois nettoyée (`trim(...) = ''`). Une cellule avec seulement des espaces paraît remplie dans Excel mais ne l'est pas : elle est donc en anomalie. Le message par défaut nomme la colonne pour être compris sans connaissance technique. Pour écrire VAL-05 (liste autorisée), tu copieras cette classe et changeras le code, la dimension et la condition (`NOT IN (?, ?)` avec la liste en paramètres).

### 9.4 — Calcul des scores (`execution/`)

`execution/RuleStatus.java`

```java
package ci.cnam.dqanalyzer.execution;

/** Statut d'une règle ou d'une analyse (CDC §7). */
public enum RuleStatus {
    OK,
    ALERT,
    KO
}
```

`execution/domain/Scoring.java`

```java
package ci.cnam.dqanalyzer.execution.domain;

import ci.cnam.dqanalyzer.execution.RuleStatus;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleResult;
import ci.cnam.dqanalyzer.rules.Severity;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Calcul des scores et statuts (CDC §7). Seul endroit du code où ce calcul existe.
 *
 * <p>Choix à valider avec le métier : une règle qui ne contrôle aucune ligne (0 ligne concernée)
 * n'a pas de score et n'entre pas dans les moyennes ; son statut est OK.
 */
public final class Scoring {

    private final Map<Severity, Integer> severityWeights;

    /** Poids par défaut : Bloquante 3, Majeure 2, Mineure 1. */
    public Scoring() {
        this(defaultWeights());
    }

    /** Poids personnalisés (modifiables par l'administrateur). */
    public Scoring(Map<Severity, Integer> severityWeights) {
        var weights = new EnumMap<Severity, Integer>(Severity.class);
        for (Severity severity : Severity.values()) {
            int weight = Objects.requireNonNull(severityWeights.get(severity), "Poids manquant pour " + severity);
            if (weight <= 0) {
                throw new IllegalArgumentException("Le poids de " + severity + " doit être positif");
            }
            weights.put(severity, weight);
        }
        this.severityWeights = Map.copyOf(weights);
    }

    private static Map<Severity, Integer> defaultWeights() {
        var weights = new EnumMap<Severity, Integer>(Severity.class);
        for (Severity severity : Severity.values()) {
            weights.put(severity, severity.defaultWeight());
        }
        return weights;
    }

    /** Score d'une règle = conformes / contrôlées × 100 ; vide si aucune ligne contrôlée. */
    public OptionalDouble ruleScore(RuleResult result) {
        if (result.checkedRows() == 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(result.conformingRows() * 100.0 / result.checkedRows());
    }

    public RuleStatus ruleStatus(OptionalDouble score, double thresholdPercent, Severity severity) {
        if (score.isEmpty() || score.getAsDouble() >= thresholdPercent) {
            return RuleStatus.OK;
        }
        return severity == Severity.BLOCKING ? RuleStatus.KO : RuleStatus.ALERT;
    }

    /** Score d'une dimension = moyenne des scores de ses règles pondérée par la sévérité. */
    public Map<Dimension, Double> dimensionScores(List<ScoredRule> rules) {
        var sums = new EnumMap<Dimension, double[]>(Dimension.class);
        for (ScoredRule rule : rules) {
            if (rule.score().isEmpty()) {
                continue;
            }
            int weight = severityWeights.get(rule.severity());
            double[] acc = sums.computeIfAbsent(rule.dimension(), d -> new double[2]);
            acc[0] += weight * rule.score().getAsDouble();
            acc[1] += weight;
        }
        var scores = new EnumMap<Dimension, Double>(Dimension.class);
        sums.forEach((dimension, acc) -> scores.put(dimension, acc[0] / acc[1]));
        return scores;
    }

    /**
     * Score global = moyenne des dimensions mesurées, pondérée par les poids du modèle (1 par défaut).
     * Une dimension absente de {@code dimensionScores} est « Non mesurée » et n'entre pas dans le calcul.
     */
    public OptionalDouble globalScore(Map<Dimension, Double> dimensionScores, Map<Dimension, Double> dimensionWeights) {
        double sum = 0;
        double totalWeight = 0;
        for (var entry : dimensionScores.entrySet()) {
            double weight = dimensionWeights.getOrDefault(entry.getKey(), 1.0);
            sum += weight * entry.getValue();
            totalWeight += weight;
        }
        return totalWeight == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / totalWeight);
    }

    /** Score d'une colonne : même calcul pondéré, restreint aux règles portant sur la colonne. */
    public OptionalDouble columnScore(String column, List<ScoredRule> rules) {
        double sum = 0;
        double totalWeight = 0;
        for (ScoredRule rule : rules) {
            if (rule.columns().contains(column) && rule.score().isPresent()) {
                int weight = severityWeights.get(rule.severity());
                sum += weight * rule.score().getAsDouble();
                totalWeight += weight;
            }
        }
        return totalWeight == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / totalWeight);
    }

    /** KO dès qu'une règle Bloquante est KO, sinon Alerte s'il existe une Alerte, sinon OK. */
    public RuleStatus globalStatus(Collection<RuleStatus> ruleStatuses) {
        if (ruleStatuses.contains(RuleStatus.KO)) {
            return RuleStatus.KO;
        }
        return ruleStatuses.contains(RuleStatus.ALERT) ? RuleStatus.ALERT : RuleStatus.OK;
    }

    /** Une règle exécutée, réduite à ce dont le calcul a besoin. */
    public record ScoredRule(Dimension dimension, Severity severity, List<String> columns, OptionalDouble score) {
        public ScoredRule {
            columns = List.copyOf(columns);
        }
    }
}
```

**Explication de `Scoring`, méthode par méthode** (toutes appliquent le CDC §7) :

- **`ruleScore`** : conformes ÷ contrôlées × 100. 6 lignes contrôlées dont 3 conformes donnent 50 %. Le résultat est un `OptionalDouble`, c'est-à-dire « un nombre ou rien » : rien quand aucune ligne n'est contrôlée.
- **`ruleStatus`** : OK si le score atteint le seuil ; sinon KO pour une règle Bloquante, Alerte pour les autres.
- **`dimensionScores`** : moyenne pondérée par la sévérité. Une règle Bloquante à 100 % (poids 3) et une Mineure à 40 % (poids 1) donnent (3 × 100 + 1 × 40) ÷ 4 = 85 %. Une dimension sans règle n'apparaît pas dans le résultat : l'écran affichera « Non mesurée ».
- **`globalScore`** : moyenne des dimensions mesurées, avec les poids du modèle (1 par défaut).
- **`columnScore`** : même calcul que la dimension, limité aux règles qui portent sur la colonne.
- **`globalStatus`** : KO dès qu'une règle Bloquante est KO, même si le score global est bon. C'est voulu par le CDC : une anomalie bloquante ne doit pas disparaître dans une moyenne.
- **Constructeur avec poids** : l'administrateur pourra changer les poids 3/2/1 ; le constructeur refuse un poids manquant ou nul.

**Pourquoi une seule classe** : si le calcul était réparti entre l'écran, l'export Excel et le PDF, une correction oubliée à un endroit afficherait deux scores différents pour la même analyse. Ici, tout le monde appelle `Scoring`.

Un point du CDC n'est pas tranché et figure en tête de `Scoring` : une règle qui ne contrôle aucune ligne (par exemple CMP-03 quand aucune ligne ne remplit la condition) n'a pas de score. Je l'ai exclue des moyennes avec un statut OK ; fais valider ce choix par ton chef de service.

Vérification : `./mvnw -q compile` passe. Ces classes ont été compilées en Java 21 et le SQL de CMP-01 exécuté sur DuckDB avec le jeu de test de l'étape 10.

## Étape 10 — Tests

**Dans IntelliJ.** Chaque test se lance avec le triangle vert dans la marge, et un clic droit sur le dossier `src/test/java` → **Run 'All Tests'** les lance tous. Les résultats s'affichent dans un arbre vert/rouge ; un clic sur un test rouge montre l'assertion qui échoue. Pour les tests avec PostgreSQL jetable, règle d'abord les variables Podman (étape 2 bis.6). La vérification qui fait foi reste `./mvnw verify` (étape 11).

Trois niveaux de tests, du plus rapide au plus complet : tests unitaires (sans base, quelques secondes), test de modularité (frontières entre modules), test d'intégration (application complète sur un PostgreSQL jetable lancé par Testcontainers). Tous les chemins Java partent de `src/test/java/ci/cnam/dqanalyzer/`.

**Explication : pourquoi tester dès le socle.** Le premier critère de recette du CDC est que chaque type de règle détecte toutes les anomalies attendues d'un fichier de test, et que les scores correspondent au calcul manuel. Ces tests en sont la preuve automatique : ils tournent à chaque `./mvnw test`, et une modification qui casse une règle est repérée en quelques secondes, avant d'arriver chez les utilisateurs. Les noms de tests sont des phrases (`detecte_valeurs_vides_espaces_et_absentes`) : quand un test échoue, son nom dit ce qui ne marche plus.

### 10.1 — Jeu de données à anomalies connues

`src/test/resources/datasets/cmp01_num_secu.csv` : 6 lignes, dont 3 en anomalie sur `num_secu` (vide, espaces, absente). `\N` signifie « valeur absente », pour la distinguer d'une cellule vide.

```csv
id;nom;num_secu
1;KOUAME;1850101234567
2;N'GUESSAN;
3;TRAORE;"   "
4;\N;\N
5;KONE;1900512345678
6;YAO;1780303456789
```

Les lignes en anomalie sont les lignes 3, 4 et 5 du fichier (l'en-tête est la ligne 1), comme l'analyste les verrait dans Excel.

**Explication du jeu de test.** Chaque ligne vérifie un cas précis : ligne 3, cellule vide ; ligne 4, cellule faite d'espaces (entre guillemets pour que l'éditeur ne les supprime pas) ; ligne 5, valeur absente. Les lignes 2, 6 et 7 sont conformes. On connaît donc à l'avance le résultat exact : 6 contrôlées, 3 conformes, anomalies aux lignes 3, 4 et 5. Le séparateur `;` est celui des fichiers Excel exportés en français.

### 10.2 — Outils de test (`testsupport/`)

`TestDatasets.java` : charge un CSV dans DuckDB comme le fera l'import.

```java
package ci.cnam.dqanalyzer.testsupport;

import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.shared.duckdb.DuckDbIdentifiers;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

/**
 * Charge un petit CSV de src/test/resources/datasets dans une table DuckDB, comme le fera l'import :
 * colonne _row_num = n° de ligne dans le fichier (en-tête = ligne 1), autres colonnes en VARCHAR.
 * Séparateur ";", la valeur \N signifie NULL (pour distinguer « absent » de « vide »).
 */
public final class TestDatasets {

    private TestDatasets() {}

    public static Dataset loadCsv(Connection connection, String resource) {
        var format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(';')
                .setHeader()
                .setSkipHeaderRecord(true)
                .setNullString("\\N")
                .build();
        var stream = Objects.requireNonNull(
                TestDatasets.class.getClassLoader().getResourceAsStream(resource), "Ressource introuvable : " + resource);
        try (var parser = CSVParser.parse(new InputStreamReader(stream, StandardCharsets.UTF_8), format)) {
            var columns = parser.getHeaderNames();
            String table = "ds_test";

            var ddl = new StringBuilder("CREATE TABLE " + DuckDbIdentifiers.quote(table) + " ("
                    + DuckDbIdentifiers.quote(Dataset.ROW_NUM_COLUMN) + " BIGINT");
            var placeholders = new StringBuilder("?");
            for (String column : columns) {
                ddl.append(", ").append(DuckDbIdentifiers.quote(column)).append(" VARCHAR");
                placeholders.append(", ?");
            }
            try (var st = connection.createStatement()) {
                st.execute(ddl.append(')').toString());
            }

            String insert = "INSERT INTO " + DuckDbIdentifiers.quote(table) + " VALUES (" + placeholders + ")";
            try (var ps = connection.prepareStatement(insert)) {
                long rowNum = 2;
                for (var record : parser) {
                    ps.setLong(1, rowNum++);
                    for (int i = 0; i < columns.size(); i++) {
                        ps.setString(i + 2, record.get(i));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return new Dataset(connection, table, new ArrayList<>(columns));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("Chargement du jeu de test impossible : " + resource, e);
        }
    }
}
```

**Explication.** Cette classe reproduit en petit ce que fera l'import réel, pour tester les règles dès aujourd'hui sans attendre le module `importing`. Elle lit le CSV avec Commons CSV, crée la table DuckDB avec `_row_num` puis une colonne texte par en-tête, et insère les lignes par lot (`addBatch` puis `executeBatch` : une seule opération au lieu d'une par ligne). `rowNum` commence à 2 parce que la ligne 1 du fichier est l'en-tête. `setNullString("\\N")` transforme le texte `\N` en vraie valeur absente (`null`).

`Rules.java` : fabrique de règles pour les tests.

```java
package ci.cnam.dqanalyzer.testsupport;

import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.Normalization;
import ci.cnam.dqanalyzer.rules.RuleDefinition;
import ci.cnam.dqanalyzer.rules.Severity;
import java.util.List;
import java.util.Map;

/** Fabrique de règles pour les tests. */
public final class Rules {

    private Rules() {}

    public static RuleDefinition rule(String type, Dimension dimension, List<String> columns, Map<String, Object> params) {
        return new RuleDefinition(
                1L,
                "R-TEST",
                1,
                "Règle de test " + type,
                type,
                dimension,
                columns,
                params,
                Normalization.NONE,
                Severity.MAJOR,
                95.0,
                null);
    }
}
```

**Explication.** Créer une `RuleDefinition` demande 12 valeurs ; un test ne s'intéresse qu'au type, à la colonne et aux paramètres. Cette fabrique remplit le reste avec des valeurs neutres, et les tests restent courts et lisibles.

### 10.3 — Tests unitaires

`rules/evaluators/Cmp01RequiredValueEvaluatorTest.java` : modèle à copier pour chaque nouvelle règle.

```java
package ci.cnam.dqanalyzer.rules.evaluators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ci.cnam.dqanalyzer.rules.Anomaly;
import ci.cnam.dqanalyzer.rules.Dataset;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.ReferenceProvider;
import ci.cnam.dqanalyzer.rules.RuleEvaluationException;
import ci.cnam.dqanalyzer.testsupport.Rules;
import ci.cnam.dqanalyzer.testsupport.TestDatasets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Cmp01RequiredValueEvaluatorTest {

    private final Cmp01RequiredValueEvaluator evaluator = new Cmp01RequiredValueEvaluator();
    private Connection connection;
    private Dataset dataset;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        dataset = TestDatasets.loadCsv(connection, "datasets/cmp01_num_secu.csv");
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void detecte_valeurs_vides_espaces_et_absentes() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("num_secu"), Map.of());
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.checkedRows()).isEqualTo(6);
        assertThat(result.conformingRows()).isEqualTo(3);
        assertThat(anomalies).extracting(Anomaly::rowNum).containsExactly(3L, 4L, 5L);
        assertThat(anomalies).extracting(Anomaly::value).containsExactly("", "   ", null);
        assertThat(anomalies).allSatisfy(a -> {
            assertThat(a.column()).isEqualTo("num_secu");
            assertThat(a.message()).contains("num_secu");
        });
    }

    @Test
    void colonne_entierement_renseignee_aucune_anomalie() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("id"), Map.of());
        var anomalies = new ArrayList<Anomaly>();

        var result = evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, anomalies::add);

        assertThat(result.anomalyRows()).isZero();
        assertThat(anomalies).isEmpty();
    }

    @Test
    void colonne_inconnue_message_clair() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("telephone"), Map.of());

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class)
                .hasMessageContaining("telephone");
    }

    @Test
    void nom_de_colonne_malveillant_neutralise() {
        var rule = Rules.rule("CMP-01", Dimension.COMPLETENESS, List.of("x\" OR 1=1 --"), Map.of());

        assertThatThrownBy(() -> evaluator.evaluate(dataset, rule, ReferenceProvider.NONE, a -> {}))
                .isInstanceOf(RuleEvaluationException.class);
    }
}
```

**Explication.**

- **`@BeforeEach` / `@AfterEach`** : avant chaque test, une base DuckDB neuve est créée en mémoire (`jdbc:duckdb:` sans nom de fichier) et le CSV y est chargé ; après chaque test, elle est fermée. Chaque test part ainsi d'un état propre et ne dépend pas des autres.
- **Structure de chaque test** : préparer (la règle), agir (`evaluate`), vérifier (`assertThat`). Les assertions AssertJ se lisent comme une phrase : « les numéros de ligne des anomalies contiennent exactement 3, 4, 5 ».
- **`anomalies::add`** : le récepteur d'anomalies est ici une simple liste, qu'on inspecte ensuite.
- **Quatre tests, quatre risques** : la détection elle-même ; l'absence de fausse alerte sur une colonne complète ; un message clair si la colonne n'existe pas ; une tentative d'injection SQL dans le nom de colonne, qui doit être refusée.

`rules/RuleEvaluatorRegistryTest.java`

```java
package ci.cnam.dqanalyzer.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEvaluatorRegistryTest {

    @Test
    void retrouve_un_evaluateur_par_son_type() {
        var registry = new RuleEvaluatorRegistry(List.of(fake("CMP-01"), fake("UNI-01")));

        assertThat(registry.get("UNI-01").type()).isEqualTo("UNI-01");
        assertThat(registry.types()).containsExactly("CMP-01", "UNI-01");
    }

    @Test
    void type_inconnu_refuse() {
        var registry = new RuleEvaluatorRegistry(List.of(fake("CMP-01")));

        assertThatThrownBy(() -> registry.get("XXX-99")).isInstanceOf(RuleEvaluationException.class);
    }

    @Test
    void deux_evaluateurs_du_meme_type_refuses_au_demarrage() {
        assertThatThrownBy(() -> new RuleEvaluatorRegistry(List.of(fake("CMP-01"), fake("CMP-01"))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RuleEvaluator fake(String type) {
        return new RuleEvaluator() {
            @Override
            public String type() {
                return type;
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
            public RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
                return RuleResult.of(0, 0);
            }
        };
    }
}
```

**Explication.** Ce test vérifie le registre sans démarrer Spring : on lui donne nous-mêmes de faux évaluateurs (`fake`) qui ne font rien. On contrôle les trois comportements promis : retrouver un évaluateur par son code, refuser un code inconnu, refuser deux évaluateurs du même code.

`rules/NormalizationTest.java` : vérifie la normalisation, utilisée par les prochaines règles (UNI-03, VAL-05…).

```java
package ci.cnam.dqanalyzer.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalizationTest {

    @Test
    void sans_normalisation_simple_conversion_en_texte() {
        assertThat(Normalization.NONE.apply("\"nom\"")).isEqualTo("CAST(\"nom\" AS VARCHAR)");
    }

    @Test
    void toutes_les_options_dans_l_ordre_espaces_accents_majuscules() {
        var all = new Normalization(true, true, true);

        assertThat(all.apply("\"nom\"")).isEqualTo("upper(strip_accents(trim(CAST(\"nom\" AS VARCHAR))))");
    }
}
```

**Explication.** Vérifie que le SQL produit est exactement celui attendu, sans même lancer DuckDB. Si quelqu'un change l'ordre des fonctions (majuscules avant suppression des accents, par exemple), le test le signale.

`execution/domain/ScoringTest.java` : chaque attendu est calculé à la main en commentaire, comme l'exige la recette.

```java
package ci.cnam.dqanalyzer.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import ci.cnam.dqanalyzer.execution.RuleStatus;
import ci.cnam.dqanalyzer.execution.domain.Scoring.ScoredRule;
import ci.cnam.dqanalyzer.rules.Dimension;
import ci.cnam.dqanalyzer.rules.RuleResult;
import ci.cnam.dqanalyzer.rules.Severity;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** Vérifie le calcul du CDC §7 sur des valeurs calculées à la main. */
class ScoringTest {

    private final Scoring scoring = new Scoring();

    private final List<ScoredRule> rules = List.of(
            new ScoredRule(Dimension.COMPLETENESS, Severity.BLOCKING, List.of("a"), OptionalDouble.of(100)),
            new ScoredRule(Dimension.COMPLETENESS, Severity.MINOR, List.of("b"), OptionalDouble.of(40)),
            new ScoredRule(Dimension.VALIDITY, Severity.MAJOR, List.of("a"), OptionalDouble.of(90)),
            new ScoredRule(Dimension.VALIDITY, Severity.MAJOR, List.of("a"), OptionalDouble.empty()));

    @Test
    void score_regle_conformes_sur_controlees() {
        assertThat(scoring.ruleScore(RuleResult.of(6, 3)).getAsDouble()).isEqualTo(50.0);
    }

    @Test
    void regle_sans_ligne_controlee_sans_score() {
        assertThat(scoring.ruleScore(RuleResult.of(0, 0))).isEmpty();
    }

    @Test
    void statut_regle_selon_seuil_et_severite() {
        assertThat(scoring.ruleStatus(OptionalDouble.of(80), 80, Severity.BLOCKING)).isEqualTo(RuleStatus.OK);
        assertThat(scoring.ruleStatus(OptionalDouble.of(50), 80, Severity.BLOCKING)).isEqualTo(RuleStatus.KO);
        assertThat(scoring.ruleStatus(OptionalDouble.of(50), 80, Severity.MAJOR)).isEqualTo(RuleStatus.ALERT);
        assertThat(scoring.ruleStatus(OptionalDouble.of(50), 80, Severity.MINOR)).isEqualTo(RuleStatus.ALERT);
        assertThat(scoring.ruleStatus(OptionalDouble.empty(), 80, Severity.BLOCKING)).isEqualTo(RuleStatus.OK);
    }

    @Test
    void score_dimension_pondere_par_severite() {
        var scores = scoring.dimensionScores(rules);

        // Complétude : (3×100 + 1×40) / (3+1) = 85 ; Validité : la règle sans score est ignorée → 90
        assertThat(scores).containsEntry(Dimension.COMPLETENESS, 85.0).containsEntry(Dimension.VALIDITY, 90.0);
        assertThat(scores).doesNotContainKey(Dimension.UNIQUENESS);
    }

    @Test
    void score_global_moyenne_des_dimensions_mesurees() {
        var dims = scoring.dimensionScores(rules);

        assertThat(scoring.globalScore(dims, Map.of()).getAsDouble()).isEqualTo(87.5);
        // Validité pondérée 3 : (85 + 3×90) / 4 = 88,75
        assertThat(scoring.globalScore(dims, Map.of(Dimension.VALIDITY, 3.0)).getAsDouble())
                .isCloseTo(88.75, within(1e-9));
        assertThat(scoring.globalScore(Map.of(), Map.of())).isEmpty();
    }

    @Test
    void score_colonne_restreint_aux_regles_de_la_colonne() {
        // Colonne a : (3×100 + 2×90) / (3+2) = 96
        assertThat(scoring.columnScore("a", rules).getAsDouble()).isEqualTo(96.0);
        assertThat(scoring.columnScore("z", rules)).isEmpty();
    }

    @Test
    void statut_global_ko_des_qu_une_bloquante_est_ko() {
        assertThat(scoring.globalStatus(List.of(RuleStatus.OK, RuleStatus.ALERT, RuleStatus.KO)))
                .isEqualTo(RuleStatus.KO);
        assertThat(scoring.globalStatus(List.of(RuleStatus.OK, RuleStatus.ALERT))).isEqualTo(RuleStatus.ALERT);
        assertThat(scoring.globalStatus(List.of(RuleStatus.OK))).isEqualTo(RuleStatus.OK);
    }

    @Test
    void poids_de_severite_personnalises() {
        var custom = new Scoring(Map.of(Severity.BLOCKING, 1, Severity.MAJOR, 1, Severity.MINOR, 1));

        // (100 + 40) / 2 = 70
        assertThat(custom.dimensionScores(rules)).containsEntry(Dimension.COMPLETENESS, 70.0);
    }
}
```

**Explication.** Le jeu de 4 règles fictives couvre tous les cas du CDC §7 : deux sévérités différentes dans une même dimension (pour la pondération), une règle sans score (à ignorer), une dimension sans règle (« Non mesurée »), et une colonne présente dans deux dimensions. Chaque attendu est recalculé à la main en commentaire : c'est ce calcul que tu pourras montrer en recette. `within(1e-9)` tolère une infime différence d'arrondi entre 88,75 et le résultat en nombre à virgule.

### 10.4 — Test de modularité

`ModularityTest.java` : échoue si un module utilise les classes internes d'un autre.

```java
package ci.cnam.dqanalyzer;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Vérifie qu'aucun module n'utilise les classes internes (api/, domain/, infra/) d'un autre module. */
class ModularityTest {

    @Test
    void les_modules_respectent_leurs_frontieres() {
        ApplicationModules.of(DqAnalyzerApplication.class).verify();
    }
}
```

**Explication.** `ApplicationModules.of(...)` analyse les classes compilées et reconstitue les modules (chaque sous-package direct de `ci.cnam.dqanalyzer`). `verify()` échoue s'il trouve une dépendance interdite, par exemple `execution` qui utiliserait `security/infra/AppUserRepository`, ou deux modules qui dépendent l'un de l'autre en boucle. Il n'a besoin ni de base ni de Spring : il tourne en une ou deux secondes.

### 10.5 — Test d'intégration

Ouvre `TestcontainersConfiguration.java` (généré) et vérifie qu'il contient un bean `PostgreSQLContainer` annoté `@ServiceConnection`. Remplace seulement le nom d'image par `"postgres:17-alpine"` pour tester sur la même version qu'en local. Ne change pas ses imports : start.spring.io a mis ceux qui correspondent à ta version de Testcontainers.

Remplace le contenu de `DqAnalyzerApplicationIT.java` (le fichier renommé à l'étape 2) :

```java
package ci.cnam.dqanalyzer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Démarre l'application complète sur un PostgreSQL jetable (Testcontainers) : vérifie que les migrations
 * Flyway passent, que le mapping JPA est valide et que la sécurité protège les pages.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DqAnalyzerApplicationIT {

    @Autowired
    MockMvc mvc;

    @Test
    void page_accueil_exige_une_connexion() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void page_de_connexion_accessible() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void point_de_sante_accessible() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void mauvais_identifiants_refuses() throws Exception {
        mvc.perform(formLogin().user("inconnu").password("faux"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }
}
```

**Explication.**

- **`@SpringBootTest`** : démarre l'application entière, comme en vrai.
- **`@Import(TestcontainersConfiguration.class)`** : avant le démarrage, Testcontainers lance un conteneur PostgreSQL neuf ; `@ServiceConnection` donne son adresse à Spring à la place de celle de `application-dev.yml`. Flyway y crée le schéma : si une migration a une erreur, le test échoue.
- **`@AutoConfigureMockMvc`** : fournit `MockMvc`, qui simule des requêtes HTTP sans ouvrir de port réseau.
- **Les quatre tests** : une page protégée renvoie vers la connexion ; la page de connexion s'affiche ; le point de santé répond (la base est joignable) ; de mauvais identifiants sont refusés. `formLogin()` envoie le formulaire de connexion avec son jeton CSRF.

Ce test prouve en une fois que le `pom.xml`, la configuration, le schéma SQL, l'entité JPA et la sécurité fonctionnent ensemble.

`@ActiveProfiles("test")` évite que le profil `dev` (actif par défaut) s'applique pendant les tests : la base vient du conteneur, et les comptes de démonstration ne sont pas chargés.

Si l'import `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` est souligné en rouge, vérifie que le `pom.xml` généré contient la dépendance `spring-boot-starter-webmvc-test` (et `spring-boot-starter-security-test` pour `formLogin`) ; sinon ajoute-les en scope `test`, sans version.

Vérification : `./mvnw test` lance les tests unitaires et le test de modularité, sans conteneur ; résultat attendu `Tests run: 18, Failures: 0, Errors: 0` (4 CMP-01, 3 registre, 2 normalisation, 8 scoring, 1 modularité).

## Étape 11 — Premier lancement et vérifications

**Dans IntelliJ.** Tape les commandes de cette étape dans le terminal intégré (**Alt+F12**), qui s'ouvre déjà à la racine du projet. Sous Windows, ce terminal est PowerShell : utilise `.\mvnw.cmd` (ou `mvnw.cmd`). Tu peux aussi double-cliquer les phases dans l'onglet **Maven** (à droite) : **Lifecycle → verify**, ou **Plugins → spotless → spotless:apply**. Le point 2 lance tout dans Podman ; ensuite, pour coder au quotidien, tu utiliseras plutôt la configuration `DQ-Analyzer (dev)` de l'étape 2 bis.5.

Sous Windows, remplace `./mvnw` par `mvnw.cmd` dans toutes les commandes.

1. **Formate le code** (une fois, pour que la vérification Spotless passe ensuite) :

```bash
./mvnw spotless:apply
```

**Explication.** Le code collé depuis ce guide n'a pas forcément l'indentation exacte du style Palantir. `spotless:apply` reformate tous les fichiers Java ; sans cela, le point 5 (`verify`) échouerait sur le contrôle de format alors que le code est correct. Prends l'habitude de le lancer avant chaque commit.

2. **Construis et démarre tout le projet dans Podman** :

```bash
podman compose up -d --build
podman ps                    # dq-postgres puis dq-app doivent passer à "healthy"
podman logs -f dq-app        # suit les logs de l'application ; Ctrl+C pour arrêter de suivre
```

**Explication.** `--build` construit l'image de l'application avec le `Dockerfile` (la première fois : quelques minutes, le temps de télécharger Maven et les bibliothèques ; ensuite, quelques dizaines de secondes). `up -d` démarre les trois conteneurs dans le bon ordre : PostgreSQL exécute ton script de création au premier lancement (étape 7), passe `healthy`, puis l'application démarre. `-d` (détaché) les laisse tourner en arrière-plan. `podman logs -f` affiche les messages de l'application en continu : c'est là que tu vois les erreurs de démarrage.

3. **Lis le démarrage dans les logs.** Tu dois voir passer `Successfully applied 1 migration to schema "public"` (Flyway) puis `Started DqAnalyzerApplication`.

**Explication.** Au démarrage, dans l'ordre : l'application se connecte au conteneur `postgres`, Flyway exécute `V1__init_schema.sql` puis `afterMigrate.sql` (les trois comptes de démo), Hibernate vérifie que l'entité `AppUser` correspond à la table, puis le serveur web s'ouvre sur le port 8080. Au démarrage suivant, Flyway affiche `Schema "public" is up to date` : il ne rejoue pas `V1`.

Commandes utiles ensuite : `podman compose up -d --build app` reconstruit et relance l'application seule après une modification du code ; `podman compose down` arrête tout en gardant les données ; `podman compose down -v` arrête tout et **efface** la base et les fichiers importés.

4. **Vérifie dans le navigateur** :

| Adresse | Résultat attendu |
| --- | --- |
| http://localhost:8080 | Redirection vers la page de connexion |
| Connexion avec `admin` / `Admin2026!` | Page d'accueil « Bienvenue, admin » |
| Connexion avec `admin` / mauvais mot de passe | Message « Identifiant ou mot de passe incorrect. » |
| Bouton « Se déconnecter » | Retour à la connexion avec « Vous êtes déconnecté. » |
| http://localhost:8080/actuator/health | `{"status":"UP"}` |
| http://localhost:8025 | Interface MailPit (vide pour l'instant) |

**Explication.** Chaque ligne du tableau vérifie une pièce différente : la redirection prouve que la sécurité protège tout par défaut ; la connexion réussie prouve que la base, l'entité et BCrypt fonctionnent ensemble ; le message d'erreur prouve que les textes en français sont bien lus ; la déconnexion prouve que le jeton CSRF circule (sinon elle échouerait avec une erreur 403) ; `health` prouve que l'application est supervisable.

Les conteneurs peuvent rester allumés pour la suite ; pour tout arrêter : `podman compose down`.

5. **Lance la vérification complète** (tests unitaires, tests d'intégration avec conteneur, formatage, couverture) :

```bash
./mvnw verify
```

**Explication.** `verify` exécute toute la chaîne Maven : compilation, tests unitaires, construction du JAR, tests d'intégration avec conteneur, contrôle du format, contrôle de couverture. C'est la commande « tout est-il prêt à être livré ? » : le `CLAUDE.md` demande qu'elle passe avant de considérer une tâche finie, et c'est elle que lancera plus tard le serveur d'intégration continue à chaque envoi de code.

Résultat attendu : `BUILD SUCCESS`. Le premier `verify` télécharge l'image PostgreSQL de test ; s'il échoue sur Testcontainers avec Podman, voir Dépannage.

Le rapport de couverture se trouve ensuite dans `target/site/jacoco/index.html`.

Alternative sans compose : `./mvnw spring-boot:test-run` lance l'application avec un PostgreSQL Testcontainers jetable (classe `TestDqAnalyzerApplication` générée). Pratique pour une démo, mais la base est vidée à chaque arrêt et les comptes de démonstration ne sont pas chargés.

## Étape 12 — Premier commit et dépôt distant

**Dans IntelliJ.** Tu peux faire cette étape soit avec les commandes ci-dessous dans le terminal intégré, soit avec l'interface : **Ctrl+K** ouvre la fenêtre de commit (coche les fichiers, écris le message, **Commit**), **Ctrl+Maj+K** envoie (push), et le nom de la branche, en haut de la fenêtre, permet d'en créer une nouvelle. Dans la fenêtre de commit, relis la liste des fichiers : aucun fichier de `storage/`, aucun `.env`, aucun `.duckdb` ne doit y figurer. S'il en apparaît un, le `.gitignore` est incomplet : corrige-le avant de valider.

Ne commite qu'après un `./mvnw verify` au vert : la branche `main` doit toujours compiler et passer les tests.

1. Vérifie ce qui va partir — aucun fichier `.env`, `storage/`, `target/` ou `.duckdb` ne doit apparaître :

```bash
git status
```

**Explication.** `git status` liste les fichiers nouveaux ou modifiés que le prochain commit peut inclure. On le lit avant chaque commit pour s'assurer que le `.gitignore` fait son travail : une fois un fichier de données ou de mots de passe envoyé sur une forge, il reste dans l'historique même si on le supprime ensuite.

2. Premier commit (format Conventional Commits, comme dans le `CLAUDE.md`) :

```bash
git add .
git commit -m "chore: socle du projet DQ-Analyzer (Spring Boot 4.1, sécurité, schéma V1, moteur de règles, CMP-01, scoring)"
```

**Explication.** `git add .` prépare tous les fichiers du dossier (sauf ceux ignorés) ; `git commit` les enregistre dans l'historique avec un message. Le format Conventional Commits commence par un type : `feat` pour une fonction nouvelle, `fix` pour une correction, `chore` pour l'outillage et la configuration, `test`, `docs`. Avec cette convention, l'historique se lit comme un journal des changements, et on peut en générer automatiquement les notes de version.

3. Crée un dépôt **vide et privé** sur la forge utilisée par la DSI (GitLab interne, GitHub, Azure DevOps) : sans README ni `.gitignore`, pour éviter un conflit au premier envoi. Puis relie-le et envoie :

```bash
git remote add origin <URL-du-dépôt>
git push -u origin main
```

**Explication.** **Dépôt privé** : le code contient le modèle de données de la CNAM, il n'a pas à être public. **Dépôt vide** : si la forge crée un README à sa création, son historique et le tien ne partagent aucun commit commun, et Git refuse l'envoi. `remote add origin` enregistre l'adresse de la forge sous le nom court `origin`. `push -u origin main` envoie la branche `main`, et `-u` mémorise le lien : les fois suivantes, un simple `git push` suffit.

4. Pour la suite, travaille par branche, une branche par lot ou par règle, puis fusion par merge request / pull request :

```bash
git switch -c feat/uni-01-valeur-unique
# ... développement, ./mvnw verify ...
git commit -m "feat(rules): évaluateur UNI-01 valeur unique sur une colonne"
git push -u origin feat/uni-01-valeur-unique
```

**Explication.** `git switch -c` crée une branche et s'y place. Tu travailles alors sur une copie de `main` : si le travail tourne mal, `main` reste intact et utilisable. La merge request permet de relire l'ensemble des changements avant de les fusionner, et de faire tourner `verify` automatiquement dessus. Une branche par règle ou par lot garde chaque changement petit, facile à relire et à annuler si besoin.

Vérification : `git log --oneline` affiche ton commit, et la forge montre les fichiers.

## Étape 13 — Travailler ensuite avec Claude Code

**Dans IntelliJ.** Lance `claude` dans le terminal intégré et installe le plugin Claude Code (étape 2 bis.8) : les modifications proposées s'ouvrent dans la vue de comparaison d'IntelliJ, où tu les relis avant de les accepter.

Le socle est conçu pour que Claude Code avance vite et sans dériver : le `CLAUDE.md` donne les règles, le CDC donne les exigences, `RowPredicateEvaluator` et le test CMP-01 servent de modèles, et `./mvnw verify` dit si le travail est fini.

Lance-le depuis la racine du projet :

```bash
cd dq-analyzer
claude
```

Bonnes pratiques :

- **Une tâche par conversation**, et une branche Git par tâche. Entre deux tâches, repars d'une conversation vide pour ne pas traîner un contexte inutile.
- **Demande un plan avant le code** pour toute tâche qui touche plusieurs fichiers (le mode plan de Claude Code sert à ça) ; corrige le plan, puis laisse-le coder.
- **Cite les codes du CDC** (UNI-01, IMP-03, MFA-02…) : Claude ira lire l'exigence exacte dans `docs/CAHIER_DES_CHARGES_V1.md`.
- **Relis chaque diff** avant le commit, surtout le SQL généré et les `@PreAuthorize`.

**Explication de ces pratiques.** Une conversation longue accumule des fichiers lus et des essais abandonnés : Claude s'y perd et devient moins précis. Repartir à zéro par tâche, avec le `CLAUDE.md` comme mémoire du projet, garde des réponses nettes. Le plan avant le code te permet de corriger une mauvaise direction en lisant dix lignes, au lieu de défaire vingt fichiers. Citer les codes du CDC évite de réexpliquer chaque exigence et garantit que Claude travaille sur le texte validé, pas sur ta reformulation. Relire le diff reste ta responsabilité : c'est toi qui signes le commit.

Premières demandes à copier, dans cet ordre :

1. Règles ligne à ligne, par paquets de 3 :

```text
Implémente les évaluateurs VAL-03, VAL-04 et VAL-05 du CDC §6 en t'appuyant sur RowPredicateEvaluator,
sur le modèle de Cmp01RequiredValueEvaluator. Pour chacun : d'abord un jeu de test CSV à anomalies
connues et le test, vérifie qu'il échoue, puis l'implémentation. Termine par ./mvnw spotless:apply verify.
```

**Explication.** Par paquets de 3, le résultat reste assez petit pour être relu en une fois. « D'abord le test, vérifie qu'il échoue » applique la méthode du `CLAUDE.md` : un test qui passe avant que la règle existe ne prouve rien. VAL-03, VAL-04 et VAL-05 sont les plus simples (longueur, plage, liste) : bon point de départ pour vérifier que le modèle `RowPredicateEvaluator` tient.

2. Règles d'unicité (passage d'ensemble, groupes de doublons) :

```text
Propose un plan pour UNI-01 et UNI-02 : ce sont des règles d'ensemble, elles ne peuvent pas utiliser
RowPredicateEvaluator. Les anomalies doivent porter le n° de groupe de doublons (RAP-04) et le résultat
le nombre de groupes (CDC §7, Unicité). Attends ma validation avant de coder.
```

**Explication.** Les doublons ne se voient pas ligne par ligne : il faut comparer chaque ligne aux autres (en SQL, un `GROUP BY` sur la clé). Il faut donc une deuxième classe de base, d'où la demande de plan et de validation : c'est un choix de conception qui servira aux 4 règles d'unicité.

3. Import CSV en flux vers DuckDB :

```text
Plan pour le module importing, premier incrément : import d'un CSV (IMP-03, IMP-05, IMP-06, IMP-07)
lu en flux et inséré par l'Appender DuckDB avec _row_num, sans jamais charger le fichier en mémoire
(PERF-03). Pas encore d'upload par morceaux.
```

**Explication.** L'import est la pièce qui transforme un vrai fichier en `Dataset`. On commence par le CSV, le format le plus simple, sans l'envoi par morceaux. Une fois le CSV en place, tu pourras analyser de vrais fichiers du service avec les règles déjà écrites, et ajouter les autres formats un par un.

4. Double authentification : MFA-01 à MFA-07, à faire avant toute mise à disposition des utilisateurs.

**Explication.** Les fichiers analysés contiennent des données personnelles d'assurés : la double authentification est obligatoire pour tous les comptes (CDC §5.8) et doit être en place avant qu'un utilisateur réel se connecte, pas après.

Ordre conseillé des lots suivants : règles restantes → import des autres formats → profilage → gestion des règles et modèles (écrans) → exécution asynchrone et rapport → exports → historique → upload par morceaux de 2 Go → tests de performance PERF-01.

**Explication de cet ordre.** Le moteur d'abord, car tout le reste en dépend et il se teste sans écran. Puis l'import, qui donne de vrais fichiers à analyser. Les écrans viennent quand le fonctionnement est sûr, ce qui évite de refaire des formulaires. L'upload de 2 Go et la performance arrivent à la fin, quand le circuit complet existe et peut être mesuré.

## Étape 14 — Mettre DQ-Analyzer sur un serveur

L'outil peut aller sur un serveur dès que l'étape 11 passe au vert, et il vaut mieux le faire tout de suite sur un serveur de recette. Il n'a pas besoin d'un nom de domaine public ; il a besoin de HTTPS, donc idéalement d'un nom interne fourni par la DSI. Sur le serveur, tout tourne dans Podman, comme sur ton poste : PostgreSQL, l'application et un proxy HTTPS.

### Quand mettre en ligne, et pour qui

| Moment | Ce qui est en ligne | Pour qui |
| --- | --- | --- |
| Fin de l'étape 11 (maintenant) | Connexion et page d'accueil | Toi et la DSI : valider serveur, réseau, HTTPS, base, sauvegarde |
| Après l'import CSV, les premières règles et le rapport | Analyses de fichiers de test | Toi et ton chef de service, en démonstration |
| Après le lot double authentification (MFA-01 à MFA-07) et l'avis du RSSI | Analyses de vrais fichiers | Analystes et lecteurs |

**Explication.** Dans une DSI, obtenir un serveur, un nom DNS, un certificat et une ouverture de pare-feu prend souvent plus de temps que d'écrire le code. Déployer le socle maintenant lance ces demandes en parallèle du développement, et chaque lot suivant se déploie en une commande. En revanche, aucun vrai fichier d'assurés ne doit être chargé avant la double authentification : c'est une exigence du CDC (§5.8) et de la loi n° 2013-450.

### Avec ou sans nom de domaine

| Accès | Exemple | Possible ? | Pourquoi |
| --- | --- | --- | --- |
| Adresse IP en HTTP | http://10.10.5.20:8080 | Non | En profil `prod`, le cookie de session est réservé au HTTPS (`cookie.secure: true`) : la connexion échoue en boucle |
| Adresse IP en HTTPS, certificat auto-signé | https://10.10.5.20 | Oui, pour une recette rapide | Fonctionne ; le navigateur affiche une alerte de sécurité à accepter |
| Nom interne + certificat de l'autorité interne | https://dq-analyzer.cnam.local | Recommandé | Adresse lisible, aucune alerte, rien d'exposé à Internet |
| Nom de domaine public sur Internet | https://dq.exemple.ci | Non | Outil interne manipulant des données personnelles : il ne doit pas être joignable depuis Internet (PSSI) |

**Explication.** Un nom de domaine n'est qu'un nom lisible qui pointe vers une adresse IP ; techniquement, l'application n'en a pas besoin. Le HTTPS, lui, est obligatoire (SEC-03), et un certificat HTTPS est délivré pour un nom (ou une IP). Le plus simple est donc de demander à l'équipe réseau un nom dans le DNS interne (`dq-analyzer.cnam.local` par exemple) et un certificat signé par l'autorité interne de la CNAM, que les postes reconnaissent déjà. En attendant, une IP avec un certificat auto-signé suffit pour tester.

### 14.1 — Ce qu'il faut demander à la DSI

- Un serveur Linux (Rocky/RHEL ou Ubuntu) avec Podman 4.4 ou plus et `podman-compose`, et un compte non administrateur pour l'outil.
- Mémoire et processeur : 4 vCPU et 8 Go de RAM pour commencer, à ajuster après le test PERF-01.
- Disque : au moins 3 × 2 Go par analyse simultanée (PERF-03), plus la base, les journaux et les sauvegardes ; 50 Go est un point de départ raisonnable.
- L'ouverture des ports 443 (et 80, redirigé vers 443) depuis le réseau des utilisateurs.
- Un nom DNS interne et un certificat de l'autorité interne (ou, au début, l'accord pour un certificat auto-signé).
- Un relais SMTP interne pour les codes 2FA par e-mail (question ouverte du CDC).

### 14.2 — Récupérer le projet sur le serveur

```bash
sudo mkdir -p /opt/dq-analyzer && sudo chown "$USER" /opt/dq-analyzer
cd /opt/dq-analyzer
git clone <URL-du-dépôt> .
```

**Explication.** Le serveur construit lui-même l'image à partir du dépôt, avec le même `Dockerfile` que ton poste : pas besoin d'y installer Java ni Maven. `/opt` est le dossier conventionnel des applications installées à la main. Si le serveur n'a pas accès à la forge, construis l'image sur ton poste (`podman build -t dq-analyzer:0.1.0 .`), exporte-la (`podman save -o dq-analyzer.tar dq-analyzer:0.1.0`), copie le fichier (`scp`) puis charge-la sur le serveur (`podman load -i dq-analyzer.tar`).

### 14.3 — Les secrets du serveur : fichier `.env`

Dans `/opt/dq-analyzer`, crée le fichier `.env` (il est dans le `.gitignore`, il ne sera jamais commité) :

```bash
POSTGRES_PASSWORD=<sortie de : openssl rand -base64 24>
DQ_DB_PASSWORD=<une autre sortie de : openssl rand -base64 24>
DQ_MAIL_HOST=<relais SMTP interne>
DQ_MAIL_PORT=25
```

```bash
chmod 600 .env
```

**Explication.** `podman compose` lit automatiquement le fichier `.env` du dossier et remplace les `${…}` de `compose.prod.yaml`. `openssl rand -base64 24` produit un mot de passe aléatoire de 32 caractères. `chmod 600` : seul ton compte peut lire ce fichier. Ces mots de passe sont lus une première fois par ton script de création de la base (étape 7) : choisis-les avant le premier lancement.

### 14.4 — `compose.prod.yaml` (à la racine du dépôt, commité)

```yaml
# Production : podman compose -f compose.prod.yaml up -d --build   (secrets lus dans .env, non versionné)
name: dq-analyzer

services:
  postgres:
    image: docker.io/library/postgres:17-alpine
    container_name: dq-postgres
    restart: always
    environment:
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?à définir dans .env}
      DQ_DB_PASSWORD: ${DQ_DB_PASSWORD:?à définir dans .env}
    volumes:
      - dq-pgdata:/var/lib/postgresql/data
      - ./db/init:/docker-entrypoint-initdb.d:ro,Z
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 20

  app:
    build: .
    image: localhost/dq-analyzer:prod
    container_name: dq-app
    restart: always
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DQ_DB_URL: jdbc:postgresql://postgres:5432/dqanalyzer
      DQ_DB_USER: dqanalyzer
      DQ_DB_PASSWORD: ${DQ_DB_PASSWORD:?à définir dans .env}
      DQ_MAIL_HOST: ${DQ_MAIL_HOST:-localhost}
      DQ_MAIL_PORT: ${DQ_MAIL_PORT:-25}
    ports:
      - "127.0.0.1:8080:8080"
    volumes:
      - dq-storage:/data
      - dq-logs:/app/logs

  proxy:
    image: docker.io/library/nginx:1.27-alpine
    container_name: dq-proxy
    restart: always
    depends_on:
      - app
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro,Z
      - ./deploy/certs:/etc/nginx/certs:ro,Z

volumes:
  dq-pgdata:
  dq-storage:
  dq-logs:
```

**Explication, par différence avec le `compose.yaml` de développement.**

- **Pas de MailPit** : les e-mails partent vers le vrai relais SMTP de la CNAM.
- **`postgres` sans `ports`** : la base n'est joignable que par les autres conteneurs, jamais depuis le réseau.
- **`${…:?à définir dans .env}`** : si une variable manque, Podman refuse de démarrer et affiche ce message, au lieu de lancer une base sans mot de passe.
- **`SPRING_PROFILES_ACTIVE: prod`** : `application-prod.yml` s'applique ; pas de comptes de démonstration, cookie réservé au HTTPS. Les variables `DQ_DB_*` sont celles qu'attend ce fichier.
- **`restart: always`** : un conteneur arrêté par une erreur ou par un redémarrage de Podman repart tout seul.
- **`app` sur `127.0.0.1:8080`** : l'application n'est pas exposée directement ; tout passe par le proxy HTTPS. Le port local sert seulement aux contrôles depuis le serveur.
- **`dq-logs:/app/logs`** : les journaux du profil `prod` (EXP-02) sont conservés hors de l'image.
- **`proxy` (nginx)** : reçoit le HTTPS sur le port 443, déchiffre, puis transmet la requête au conteneur `app` sur le réseau interne de Podman. Le certificat est installé à un seul endroit, et l'application n'a pas à gérer le chiffrement.

### 14.5 — La configuration HTTPS : `deploy/nginx.conf`

```nginx
# Port 80 : redirige tout vers HTTPS
server {
    listen 80;
    server_name _;
    return 301 https://$host$request_uri;
}

# Port 443 : HTTPS, puis transmission à l'application
server {
    listen 443 ssl;
    server_name _;

    ssl_certificate     /etc/nginx/certs/dq-analyzer.crt;
    ssl_certificate_key /etc/nginx/certs/dq-analyzer.key;
    ssl_protocols       TLSv1.2 TLSv1.3;

    client_max_body_size 25m;     # un morceau d'upload (10 Mo) + marge

    location / {
        proxy_pass http://app:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 300s;  # analyses longues
    }
}
```

**Explication.** Le premier bloc renvoie toute visite en `http://` vers `https://`. Le second présente le certificat et n'accepte que TLS 1.2 et 1.3, les versions encore sûres. `client_max_body_size` doit être au moins égal à la limite d'envoi de Spring (25 Mo), sinon nginx refuse les morceaux de fichier. Les en-têtes `X-Forwarded-*` disent à l'application que l'utilisateur est en HTTPS : grâce au réglage `forward-headers-strategy: native` de `application-prod.yml`, elle construit alors des liens et des cookies corrects.

**Le certificat.** Place les deux fichiers fournis par la DSI dans `deploy/certs/` (`dq-analyzer.crt` et `dq-analyzer.key`), et ajoute la ligne `deploy/certs/` au `.gitignore` : une clé privée ne doit jamais aller dans Git. Pour une recette rapide sans certificat officiel, fabrique un certificat auto-signé pour l'IP du serveur :

```bash
mkdir -p deploy/certs
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout deploy/certs/dq-analyzer.key -out deploy/certs/dq-analyzer.crt \
  -subj "/CN=dq-analyzer" -addext "subjectAltName=IP:10.10.5.20"   # remplace par l'IP du serveur
```

**Explication.** `-x509` produit directement un certificat signé par lui-même, `-nodes` laisse la clé sans phrase de passe (nginx doit la lire au démarrage), `subjectAltName` indique pour quelle adresse le certificat est valable. Le navigateur affichera une alerte, puisqu'aucune autorité ne l'a signé : c'est normal en recette.

### 14.6 — Lancer

Podman sans droits d'administration ne peut pas ouvrir les ports en dessous de 1024. Une fois, en administrateur :

```bash
echo "net.ipv4.ip_unprivileged_port_start=80" | sudo tee /etc/sysctl.d/90-dq-analyzer.conf
sudo sysctl --system
sudo loginctl enable-linger "$USER"
systemctl --user enable --now podman-restart.service
```

Puis, dans `/opt/dq-analyzer` :

```bash
podman compose -f compose.prod.yaml up -d --build
podman ps                                          # les trois conteneurs "Up", dq-postgres et dq-app "healthy"
curl -s http://127.0.0.1:8080/actuator/health      # {"status":"UP"}
```

**Explication.** La ligne `sysctl` autorise les comptes ordinaires à ouvrir les ports 80 et 443, et le fichier dans `/etc/sysctl.d/` rend ce réglage permanent. `enable-linger` permet à tes conteneurs de tourner même quand tu n'es pas connecté au serveur ; `podman-restart.service` relance au démarrage du serveur tous les conteneurs marqués `restart: always`. `-f compose.prod.yaml` choisit le fichier de production au lieu de `compose.yaml`.

### 14.7 — Créer le premier administrateur

En profil `prod`, aucun compte de démonstration n'est chargé : la table `app_user` est vide. Une fois l'application démarrée (Flyway a créé la table) :

1. Calcule l'empreinte BCrypt du mot de passe choisi :

```bash
podman run --rm docker.io/library/httpd:2.4-alpine htpasswd -nbBC 12 "" 'MotDePasseChoisi'
# affiche  :$2y$12$....   → garde tout ce qui suit le « : »
```

2. Insère le compte :

```bash
podman exec -it dq-postgres psql -U dqanalyzer -d dqanalyzer
```

```sql
INSERT INTO app_user (login, full_name, email, password_hash, role)
VALUES ('mnguessan', 'Mathieu Charles N''GUESSAN', 'prenom.nom@cnam.ci', '$2y$12$...', 'ADMIN');
```

**Explication.** `htpasswd` est un petit outil du serveur web Apache, présent dans l'image `httpd` : `-B` choisit BCrypt, `-C 12` le coût 12 (comme `SecurityConfig`), `-n` affiche le résultat au lieu d'écrire un fichier. Spring accepte le préfixe `$2y$` comme `$2a$`. Dans l'INSERT, l'apostrophe de N'GUESSAN est doublée (`N''GUESSAN`), c'est ainsi qu'on l'écrit en SQL. Les comptes suivants se créeront depuis l'écran d'administration, quand il existera.

### 14.8 — Mettre à jour et sauvegarder

```bash
# Nouvelle version : récupérer le code, reconstruire et relancer l'application seule
git pull && podman compose -f compose.prod.yaml up -d --build app

# Sauvegarde de la base (à planifier chaque nuit avec cron ou un timer systemd)
mkdir -p ~/backups
podman exec dq-postgres pg_dump -U dqanalyzer -Fc dqanalyzer > ~/backups/dq-$(date +%F).dump
```

**Explication.** Au redémarrage, Flyway applique tout seul les nouvelles migrations (`V2`, `V3`…) : la mise à jour du schéma ne demande aucune action. `pg_dump -Fc` produit une sauvegarde compressée qui se restaure avec `pg_restore`. Copie ces fichiers hors du serveur (selon la politique de sauvegarde de la DSI) : une sauvegarde sur le même disque ne protège pas d'une panne de ce disque.

Vérification : depuis ton poste, https://dq-analyzer.cnam.local (ou https://10.10.5.20) affiche la page de connexion, et tu te connectes avec le compte créé en 14.7.

## Dépannage

| Symptôme | Cause probable | Solution |
| --- | --- | --- |
| `release version 21 not supported` ou `invalid target release: 21` | Maven utilise un autre JDK | Fais pointer `JAVA_HOME` vers le JDK 21, rouvre le terminal |
| `./mvnw: Permission denied` (Linux/macOS) | Script non exécutable | `chmod +x mvnw` |
| `Could not transfer artifact` / délai dépassé | Proxy réseau de la CNAM | Déclare le proxy dans `~/.m2/settings.xml` (section `<proxies>`) ou demande l'adresse du miroir Maven interne |
| `Could not find artifact … :jar:x.y.z` | Version inexistante dans le dépôt | `./mvnw versions:display-dependency-updates`, puis corrige la version dans `<properties>` |
| `Address already in use: 5432` | Un PostgreSQL est déjà installé sur le poste | Dans `compose.yaml` mets `"5433:5432"` et dans `application-dev.yml` le port `5433` |
| `Migration checksum mismatch for migration version 1` | `V1__init_schema.sql` modifié après son exécution | En dev uniquement : `podman compose down -v` (efface la base locale) puis `podman compose up -d`. Plus tard, toute correction = nouvelle migration `V2__…` |
| `Schema-validation: missing column` | L'entité Java et le SQL ne correspondent pas | Compare les noms de colonnes de l'entité (`@Column(name=…)`) avec la migration |
| Connexion toujours refusée avec `admin` | Profil `dev` non actif : comptes non chargés | Les logs doivent afficher `The following 1 profile is active: "dev"` ; sinon lance avec `-Dspring-boot.run.profiles=dev` |
| Page de connexion sans style | Bootstrap non servi | Ouvre http://localhost:8080/webjars/bootstrap/css/bootstrap.min.css : une 404 signifie que `webjars-locator-lite` ou `bootstrap` manque dans le `pom.xml` |
| Accents remplacés par des `?` ou `Ã©` | Fichier enregistré en Windows-1252 | Réenregistre le fichier en UTF-8 (étape 6) |
| `Could not find a valid Docker environment` (tests d'intégration) | Testcontainers ne trouve pas Podman | Linux : `systemctl --user enable --now podman.socket`, puis `export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock` et `export TESTCONTAINERS_RYUK_DISABLED=true`. Windows/macOS : active la compatibilité Docker dans Podman Desktop, puis relance le terminal |
| `The following files had format violations` | Code non formaté | `./mvnw spotless:apply`, puis relance |
| `Rule violated for package … lines covered ratio` | Couverture sous 70 % sur `rules` ou `execution` | Ajoute les tests manquants ; le rapport `target/site/jacoco/index.html` montre les lignes non couvertes |
| `ModularityTest` échoue avec `depends on non-exposed type` | Un module utilise une classe interne d'un autre | Passe par une classe publique à la racine du module visé, ou déplace la classe |

Si une erreur n'est pas dans ce tableau, copie le message complet (les 20 premières lignes de la trace suffisent) dans Claude Code en précisant l'étape du guide où tu es.

## Sources

Versions vérifiées le 23 octobre 2026. Les versions des autres bibliothèques (POI, Commons CSV et Text, Tika, OpenPDF, TOTP, ZXing, WebJars, Spotless, JaCoCo) n'ont pas pu être contrôlées en ligne : la commande de l'étape 3 les confirme ou les corrige.

- [Spring Boot 4.1.0 available now](https://spring.io/blog/2026/06/10/spring-boot-4/) — sortie de la branche 4.1
- [Spring Boot 4.1.1 available now](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/) — dernière version corrective publiée
- [Spring Modulith 2.2 M1, 2.1.1, 2.0.8 and 1.4.13 released](https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/) — Modulith 2.1.x pour Boot 4.1
- [DuckDB — Installation](https://www.duckdb.org/install) — DuckDB 2.0.0 (LTS), pilote JDBC `org.duckdb:duckdb_jdbc:2.0.0.0`
- [Testcontainers PostgreSQL module](https://testcontainers.com/modules/postgresql/) — artefact `testcontainers-postgresql` 2.x
- [OpenRewrite — Add @AutoConfigureMockMvc (Spring Boot 4)](https://docs.openrewrite.org/recipes/java/spring/boot4/addautoconfiguremockmvc-community-edition) — nouveau package de `AutoConfigureMockMvc`
- [Spring Boot 4 migration instructions](https://awesome-copilot.github.com/instruction/springboot-4-migration/) — starters renommés (`webmvc`) et starters de test par module
