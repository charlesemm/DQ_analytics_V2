# Guide de déploiement de DQ-Analyzer sur `srv-gouv`

Ce guide se suit du haut vers le bas, une étape à la fois. Chaque étape donne la
commande, **ce que tu dois voir**, et quoi faire si ça échoue. Les explications
longues sont à l'étape 14 de `partie_1.md` ; ici, on exécute.

Deux terminaux sont utilisés. Ils ne sont jamais interchangeables :

| Repère | Où | Quoi |
| --- | --- | --- |
| **Poste** | PowerShell, dans `C:\Users\charles.nguessan\DQ\DQ_analytics` | envoi du code |
| **Serveur** | ta session SSH `adm-mnguessan@10.10.4.165` | tout le reste |

Rappel de la contrainte qui explique tout le reste : le serveur héberge déjà
**Socle** et **ÉCHO · CNAM-CI** sur les ports 80 et 443, derrière le nginx du
système. On ne prend pas un port à part : DQ-Analyzer se range derrière ce même
portier, sous le chemin **`/dq/`** du domaine d'ÉCHO — exactement comme
`socle.ipscnam.ci/referentiel/` vit à côté de Socle.

```
navigateur --https--> nginx du système (443, echo.ipscnam.ci)
                        |-- /      --> 127.0.0.1:8000   ÉCHO
                        `-- /dq/   --> 127.0.0.1:8081   nous
```

Adresse finale : **https://echo.ipscnam.ci/dq/**

Ce qui change par rapport à la version précédente de ce guide, où nous avions
notre propre nginx sur le port 8443 : plus de certificats à transporter, plus de
port à ouvrir dans le pare-feu, plus de conteneur `dq-proxy`. En échange, **on
modifie un fichier qui sert ÉCHO** — une ligne `include` dans son vhost. C'est
le seul geste du guide qui puisse faire tomber le voisin, d'où le `nginx -t`
systématique avant tout rechargement.

---

## Étape 0 — Avant de commencer

À vérifier une fois, sur ton **poste** :

```powershell
git log --oneline -1          # doit afficher le commit "feat(deploiement)..."
dir deploy\nginx              # doit lister dq-dans-echo.conf
ssh adm-mnguessan@10.10.4.165 "hostname && podman --version"
ssh adm-mnguessan@10.10.4.165 "sudo -n nginx -t"
```

**Ce que tu dois voir** : le nom du serveur, `podman 4.x` ou plus, puis
`syntax is ok` et `test is successful`.

**Si `podman` est introuvable** : arrête-toi ici. L'installer modifie la machine
partagée ; c'est la seule action de ce guide qui demande l'accord de
l'administrateur du serveur.

**Si `sudo -n nginx -t` est refusé** : tu n'as pas la main sur le nginx du
système, et les étapes 2 et 5 sont impossibles en l'état. Deux issues : obtenir
ce droit, ou demander à l'administrateur d'appliquer lui-même ces deux étapes —
elles tiennent en une copie de fichier et une ligne ajoutée.

---

## Étape 1 — Mettre le code sur le serveur

Deux variantes. **La B ne dépend pas de GitHub** : choisis-la si le serveur n'a
pas accès à Internet, ou si tu ne veux pas encore publier la branche.

### Variante A — par Git (si le serveur atteint github.com)

Sur ton **poste** :

```powershell
git push -u origin feat/deploiement-srv-gouv
```

Fusionne ensuite la pull request sur GitHub, puis sur le **serveur** :

```bash
mkdir -p ~/dq-analyzer && cd ~/dq-analyzer
git clone https://github.com/charlesemm/DQ_analytics_V2.git .
```

**Si le dépôt est privé**, `git clone` demandera un identifiant : utilise un
jeton d'accès personnel GitHub comme mot de passe, pas ton mot de passe de compte.

### Variante B — par transfert direct (aucune dépendance à GitHub)

Sur ton **poste** :

```powershell
git archive --format=tar.gz -o dq-analyzer.tar.gz HEAD
scp dq-analyzer.tar.gz adm-mnguessan@10.10.4.165:~/
```

Puis sur le **serveur** :

```bash
mkdir -p ~/dq-analyzer && tar -xzf ~/dq-analyzer.tar.gz -C ~/dq-analyzer
rm ~/dq-analyzer.tar.gz
cd ~/dq-analyzer && ls
```

**Ce que tu dois voir** : `compose.prod.yaml`, `Dockerfile`, `deploy/`, `src/`, `db/`.

**Pourquoi `git archive` et non un copier-coller du dossier** : il n'envoie que
les fichiers commités. Ni `target/`, ni `.env`, ni les clés privées ne partent,
même par erreur. Revers de la médaille : ce qui n'est pas commité n'arrive pas
sur le serveur. Les secrets, eux, se créent sur place à l'étape 4.

Le dossier d'installation est `~/dq-analyzer`, dans ton répertoire personnel, et
non `/opt` : pas de `sudo`, et le projet ne se mélange pas à ceux des autres.

---

## Étape 2 — Déposer le relais nginx (sans encore l'activer)

Notre configuration nginx est dans le dépôt, à `deploy/nginx/dq-dans-echo.conf` :
elle décrit l'emplacement `/dq/` et vers où le relayer. On la copie maintenant là
où nginx saura la lire ; elle ne sera *appelée* qu'à l'étape 5, l'application
debout.

Sur le **serveur** :

```bash
sudo mkdir -p /etc/nginx/snippets
sudo cp ~/dq-analyzer/deploy/nginx/dq-dans-echo.conf /etc/nginx/snippets/dq.conf
sudo nginx -t
```

**Ce que tu dois voir** : `syntax is ok` et `test is successful`. Le fichier
déposé ne change encore rien — aucun vhost ne l'appelle, nginx ne le lit pas.

**Pourquoi `/etc/nginx/snippets/` et non `/etc/nginx/conf.d/`** : les fichiers de
`conf.d/` sont chargés au niveau `http`, où un `location` n'a aucun sens. nginx
refuserait de démarrer — et ÉCHO tomberait avec lui.

**Plus aucun certificat à transporter** : c'est le nginx du système qui termine le
TLS, avec le wildcard `*.ipscnam.ci` déjà en service pour Socle, ÉCHO et la
messagerie. On ne le lit pas, on ne le copie pas, et surtout on ne le monte pas
dans un conteneur : **ne jamais** monter un fichier de `/etc/nginx/` avec
l'option `:Z` de Podman — elle le réétiquetterait (SELinux) et **ÉCHO tomberait**.

---

## Étape 3 — Vérifier la place et le port

Sur le **serveur** :

```bash
df -h /home                      # au moins 50 Go libres
free -g                          # au moins 8 Go de RAM
ss -tlnp | grep ':8081' || echo "8081 libre"
```

**Ce que tu dois voir** : `8081 libre`.

**Si 8081 est déjà pris** : quelqu'un s'y est installé entre-temps — 8000
appartient à ÉCHO, n'y touche pas. Choisis 8082 et change les **deux** endroits
qui le mentionnent : le `ports:` du service `app` dans `compose.prod.yaml`, et le
`proxy_pass` de `deploy/nginx/dq-dans-echo.conf` — puis refais l'étape 2.

**Aucun port à ouvrir dans le pare-feu** : 8081 n'est publié que sur la boucle
locale, et le seul port exposé au réseau reste le 443 du nginx du système, déjà
ouvert pour ÉCHO.

**Pourquoi la place disque** : une analyse DuckDB consomme jusqu'à 3 fois la
taille du fichier importé (PERF-03). Un disque plein ferait tomber les
applications voisines, pas seulement la nôtre.

---

## Étape 4 — Les mots de passe (`.env`)

Sur le **serveur** :

```bash
cd ~/dq-analyzer
openssl rand -base64 24          # valeur 1 -> POSTGRES_PASSWORD
openssl rand -base64 24          # valeur 2 -> DQ_DB_PASSWORD
```

Recopie les deux sorties, puis crée le fichier :

```bash
cp .env.example .env
vi .env                          # colle les deux valeurs après le "="
chmod 600 .env
grep -c . .env                   # doit afficher 4 (quatre lignes renseignées)
```

Dans `vi` : `i` pour écrire, `Échap` puis `:wq` pour enregistrer et sortir.

**Ces deux mots de passe doivent être choisis maintenant** : le premier
démarrage de PostgreSQL exécute `db/init/01-create-database.sh`, qui crée le
compte applicatif avec `DQ_DB_PASSWORD`. Le changer après coup ne changerait
rien dans la base.

**`DQ_MAIL_HOST`** : déjà renseigné à `mail.ipscnam.ci` dans `.env.example`, tu
n'y touches pas. Si ce relais exige une authentification, l'envoi des codes 2FA
échouera plus tard avec un `535 Authentication required` dans les logs : il
faudra alors un compte de service et deux lignes de configuration
(`spring.mail.username` et `password`), ce qui n'est pas encore en place.

---

## Étape 5 — Brancher DQ-Analyzer sur le vhost d'ÉCHO

**C'est le seul geste du guide qui touche un fichier servant ÉCHO.** Il tient en
une ligne, et il se vérifie avant d'être appliqué.

Sur le **serveur**, sauvegarder d'abord le vhost :

```bash
sudo cp /etc/nginx/conf.d/echo.ipscnam.ci.conf ~/echo.ipscnam.ci.conf.avant-dq
sudo vi /etc/nginx/conf.d/echo.ipscnam.ci.conf
```

Ajoute cette ligne **à l'intérieur** du bloc `server { ... }` qui écoute en `443`,
juste avant le `location / {` final :

```nginx
    include /etc/nginx/snippets/dq.conf;
```

Puis, toujours sur le **serveur** :

```bash
sudo nginx -t
sudo systemctl reload nginx
curl -sk -I https://echo.ipscnam.ci/ | head -1     # ÉCHO : 200 ou 405
```

**Ce que tu dois voir** : `test is successful`, puis ÉCHO qui répond comme avant.
`/dq/` répondra `502` jusqu'à l'étape 6 : c'est normal, le conteneur n'existe pas
encore.

**`nginx -t` n'est pas une formalité** : un seul nginx sert Socle, ÉCHO et nous.
Rechargé avec une erreur de syntaxe, il les coupe tous les trois. Si `nginx -t`
échoue, **ne recharge pas** — restaure, puis recommence :

```bash
sudo cp ~/echo.ipscnam.ci.conf.avant-dq /etc/nginx/conf.d/echo.ipscnam.ci.conf
sudo nginx -t
```

`reload` et non `restart` : le rechargement ne coupe aucune connexion en cours.

**La même ligne est déjà versionnée** dans le dépôt d'ÉCHO — `simulateur_V5`,
fichier `deploiement/nginx/echo.ipscnam.ci.conf` — commentée et expliquée. Le
fichier du serveur et celui du dépôt doivent rester identiques : sinon, le
prochain déploiement d'ÉCHO écraserait notre ligne et `/dq/` rendrait un 404.

---

## Étape 6 — Lancer

Sur le **serveur** :

```bash
cd ~/dq-analyzer
podman compose -f compose.prod.yaml up -d --build
```

**Compte plusieurs minutes** : le serveur compile l'application lui-même, dans
un conteneur (c'est pour cela qu'il n'a besoin ni de Java ni de Maven). Les
lignes qui défilent sont le téléchargement des bibliothèques Maven.

```bash
podman ps
```

**Ce que tu dois voir** : deux conteneurs `Up` — `dq-postgres` marqué `healthy`
et `dq-app`. `dq-app` peut rester `starting` pendant 90 secondes : c'est le temps
laissé à Spring et à Flyway.

**Si un conteneur est absent ou `Exited`** :

```bash
podman logs dq-app | tail -40
podman logs dq-postgres | tail -20
```

| Message dans les logs | Cause | Solution |
| --- | --- | --- |
| `à définir dans .env` | une variable manque | revoir l'étape 4 |
| `password authentication failed` | le `.env` a changé après le 1er démarrage | `podman compose -f compose.prod.yaml down -v` puis refaire l'étape 6 (efface la base) |
| `Connection refused` vers postgres | la base n'était pas prête | `podman restart dq-app` |

---

## Étape 7 — Vérifier : les voisins d'abord, nous ensuite

L'ordre compte. On s'assure de n'avoir rien cassé **avant** de se réjouir.

Sur le **serveur** :

```bash
curl -sk -I https://echo.ipscnam.ci/ | head -1                      # ÉCHO : 200 ou 405
systemctl is-active nginx                                            # active
podman exec dq-app curl -s localhost:8080/dq/actuator/health         # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/dq/   # 200 ou 302
curl -I https://echo.ipscnam.ci/dq/ | head -1                        # 200 ou 302
```

La quatrième commande interroge l'application **sans passer par nginx** : elle
sépare « l'application est en panne » de « le relais est mal branché ». La
dernière est volontairement **sans `-k`** : si elle réussit, le certificat est
pleinement validé, donc aucun navigateur n'affichera d'alerte.

| Symptôme | Cause | Solution |
| --- | --- | --- |
| ÉCHO ne répond plus | la ligne `include`, ou le fichier déposé à l'étape 2 | `sudo nginx -t` ; au besoin restaurer `~/echo.ipscnam.ci.conf.avant-dq` (étape 5) |
| `404` sur `/dq/` alors qu'ÉCHO répond | la ligne `include` manque dans le vhost | revoir l'étape 5 |
| `404` sur toutes nos pages | `context-path` et `proxy_pass` désaccordés | `DQ_CONTEXT_PATH` et le chemin du `location` doivent être le même `/dq` |
| `502 Bad Gateway` sur `/dq/` | le conteneur n'écoute pas, ou SELinux bloque la sortie de nginx | `podman ps`, puis `sudo setsebool -P httpd_can_network_connect 1` |
| Pages sans style, boutons inertes | une adresse JavaScript non préfixée | console du navigateur (F12) : une requête en `/api/...` au lieu de `/dq/api/...` |

---

## Étape 8 — Créer le premier administrateur

En profil `prod`, aucun compte de démonstration n'est chargé : la table
`app_user` est vide. Flyway l'a créée au démarrage ; il reste à y mettre un compte.

Sur le **serveur**, fabriquer l'empreinte du mot de passe :

```bash
podman run --rm docker.io/library/httpd:2.4-alpine htpasswd -nbBC 12 "" 'RemplaceParTonMotDePasse'
```

**Ce que tu dois voir** : une ligne commençant par `:$2y$12$`. Garde tout ce qui
suit les deux-points, le `$2y$12$` compris.

```bash
podman exec -it dq-postgres psql -U dqanalyzer -d dqanalyzer
```

```sql
INSERT INTO app_user (login, full_name, email, password_hash, role)
VALUES ('mnguessan', 'Mathieu Charles N''GUESSAN', 'prenom.nom@ipscnam.ci', '$2y$12$...', 'ADMIN');
SELECT login, role FROM app_user;
\q
```

L'apostrophe de `N'GUESSAN` est **doublée** : c'est ainsi qu'on écrit une
apostrophe en SQL. Les comptes suivants se créeront depuis l'écran
d'administration, quand il existera.

---

## Étape 9 — Survivre à la déconnexion et au redémarrage

Sur le **serveur** :

```bash
loginctl enable-linger "$USER"
systemctl --user enable --now podman-restart.service
systemctl --user is-enabled podman-restart.service     # enabled
```

Sans `enable-linger`, tes conteneurs s'arrêtent quand tu fermes ta session SSH.
**Si ces deux commandes sont refusées faute de droits**, l'application
fonctionne quand même, mais tombera à ta déconnexion : c'est le seul point du
guide qui demande alors l'intervention de l'administrateur.

---

## Étape 10 — Se connecter depuis ton poste

Ouvre **https://echo.ipscnam.ci/dq/**

**Ce que tu dois voir** : la page de connexion, le cadenas fermé, aucune alerte
de sécurité. Connecte-toi avec le compte de l'étape 8.

**La barre oblique finale compte** : `/dq` sans elle est redirigé vers `/dq/` par
le `location = /dq` du fichier déposé à l'étape 2. Sans cette redirection,
l'adresse que tout le monde tape donnerait un 404 d'ÉCHO.

**Si le navigateur ne trouve pas le nom** : ajoute `10.10.4.165 echo.ipscnam.ci`
dans `C:\Windows\System32\drivers\etc\hosts` (à ouvrir en administrateur). Ce nom
est celui d'ÉCHO : s'il résout déjà, n'y touche pas.

**Plus de numéro de port dans l'URL**, et plus rien à demander à la DSI : le nom
`echo.ipscnam.ci` existe déjà. Le jour où DQ-Analyzer méritera son propre nom, un
enregistrement A `dq.ipscnam.ci` vers 10.10.4.165 suffirait — le wildcard le
couvre — avec un vhost copié sur celui d'ÉCHO et `DQ_CONTEXT_PATH=/` dans le
`.env`.

---

## Étape 11 — Vivre avec

### Mettre à jour l'application

```bash
cd ~/dq-analyzer
git pull                                                     # ou refaire l'étape 1 variante B
podman compose -f compose.prod.yaml up -d --build app
```

**Rien à redémarrer du côté de nginx** : il relaie vers `127.0.0.1:8081`, une
adresse fixe, et non vers un nom de conteneur dont l'IP changerait à chaque
reconstruction. C'était la raison du `podman restart dq-proxy` de l'ancienne
version de ce guide — disparu avec le conteneur.

Flyway applique seul les nouvelles migrations : rien à faire sur le schéma.

### Changer un paramètre (relais SMTP, par exemple)

```bash
vi .env                                                      # DQ_MAIL_HOST=mail.ipscnam.ci
podman compose -f compose.prod.yaml up -d app
```

### Sauvegarder la base

```bash
mkdir -p ~/backups
podman exec dq-postgres pg_dump -U dqanalyzer -Fc dqanalyzer > ~/backups/dq-$(date +%F).dump
ls -lh ~/backups | tail -3
```

À planifier chaque nuit (timer systemd utilisateur) et **à recopier hors du
serveur** : une sauvegarde sur le même disque ne protège pas d'une panne de ce
disque, et elle occupe l'espace des applications voisines.

Restauration :

```bash
podman exec -i dq-postgres pg_restore -U dqanalyzer -d dqanalyzer --clean < ~/backups/dq-AAAA-MM-JJ.dump
```

### Regarder ce qui se passe

```bash
podman logs -f dq-app            # journal applicatif (Ctrl+C pour sortir)
podman stats --no-stream         # mémoire et processeur des deux conteneurs
df -h /home                      # surveiller le disque (DuckDB)
```

### Tout arrêter

```bash
podman compose -f compose.prod.yaml down       # arrêter, garder la base
```

Les voisins ne s'en aperçoivent pas : seul `/dq/` devient un `502`, le temps de
l'arrêt. La ligne `include` peut rester en place — nginx démarre très bien avec
un emplacement qui ne relaie vers rien.

Pour aller plus loin — effacer les données, les secrets, le dossier, le relais
nginx — l'étape 12 reprend le retrait complet, dans l'ordre et avec ses
vérifications.

---

## Étape 12 — Retirer DQ-Analyzer du serveur

Cette étape défait ce que les étapes 1 à 9 ont installé. Elle se suit du haut
vers le bas, comme le reste du guide, et reste dans ta session SSH sauf mention
contraire.

Une précaution commande tout : nous partageons le nginx du système avec **Socle**
et **ÉCHO · CNAM-CI**. Deux choses y portent notre marque, et elles seules :
le fichier `/etc/nginx/snippets/dq.conf` et la ligne `include` du vhost d'ÉCHO.
Les retirer se fait **dans cet ordre-là, à l'envers** — la ligne d'abord, le
fichier ensuite (section 12.9) : l'inverse laisserait un `include` vers rien, et
nginx refuserait de démarrer, ÉCHO avec lui. Tout le reste de ce qui nous
appartient porte le préfixe `dq-` ou le nom de projet `dq-analyzer` : **rien
d'autre ne doit être supprimé.**

### 12.1 — Choisir le niveau

Trois niveaux. Ils s'emboîtent : le C contient le B, qui contient le A.

| Niveau | Ce qui disparaît | Ce qui reste | Sections à suivre |
| --- | --- | --- | --- |
| **A — Arrêter** | les deux conteneurs | base, fichiers importés, comptes, code, `.env`, relais nginx | 12.3, 12.4, 12.6, 12.7 |
| **B — Effacer** | conteneurs **et** données (base, fichiers importés, journaux) | code, `.env`, relais nginx | 12.2, 12.3, 12.5 à 12.8 |
| **C — Table rase** | tout, y compris le dossier, les secrets, le relais nginx et le démarrage automatique | rien : la machine revient à son état d'avant l'étape 1 | 12.2 à 12.12 |

**En cas de doute, prends le A.** Il libère la mémoire et le port local, et un
`podman compose -f compose.prod.yaml up -d` suffit à tout retrouver.

Le niveau B est **irréversible** : les comptes créés à l'étape 8 et les
fichiers importés sont perdus. D'où la section suivante.

### 12.2 — Sauvegarder (niveaux B et C)

À faire **avant** toute suppression, tant que `dq-postgres` tourne encore.

```bash
mkdir -p ~/backups
podman exec dq-postgres pg_dump -U dqanalyzer -Fc dqanalyzer > ~/backups/dq-avant-suppression-$(date +%F).dump
ls -lh ~/backups | tail -3
```

**Ce que tu dois voir** : un fichier `.dump` de taille non nulle.

**S'il fait 0 octet** : le conteneur ne tournait pas, ou le mot de passe a
changé. Ne continue pas : sans sauvegarde valable, la section 12.5 est
définitive.

Cette sauvegarde vit dans `~/backups`, sur le même disque que le reste.
**Recopie-la sur ton poste** si elle compte vraiment — depuis le **poste** :

```powershell
scp adm-mnguessan@10.10.4.165:~/backups/dq-avant-suppression-*.dump .
```

Elle ne contient que la base PostgreSQL (comptes, règles, modèles, historique).
Les fichiers importés et les bases DuckDB du volume `dq-storage` n'y sont pas :
ils se rechargent depuis les fichiers d'origine.

### 12.3 — Repérer ce qui nous appartient

```bash
cd ~/dq-analyzer
podman ps -a --filter name=dq- --format '{{.Names}}\t{{.Status}}'
podman volume ls --filter name=dq-analyzer
```

**Ce que tu dois voir** : exactement deux conteneurs — `dq-postgres` et
`dq-app` — et trois volumes — `dq-analyzer_dq-pgdata`,
`dq-analyzer_dq-storage`, `dq-analyzer_dq-logs`.

**Si un nom inconnu apparaît dans ces listes** : arrête-toi. Quelqu'un d'autre
s'est peut-être servi du même préfixe. Vérifie avant de supprimer :

```bash
podman inspect <nom> --format '{{.Config.Labels}}'
```

Nos conteneurs portent le label `com.docker.compose.project=dq-analyzer`.

### 12.4 — Arrêter (niveau A)

```bash
cd ~/dq-analyzer
podman compose -f compose.prod.yaml down
```

Les volumes sont conservés : la base, les comptes et les fichiers importés
restent intacts. Passe directement à la section 12.6.

### 12.5 — Effacer, données comprises (niveaux B et C)

```bash
cd ~/dq-analyzer
podman compose -f compose.prod.yaml down -v
```

Le `-v` supprime les trois volumes. **C'est l'étape sans retour** : si la
section 12.2 a été sautée, les comptes et les données sont perdus.

**Ce que tu dois voir** : une ligne de suppression par conteneur, puis une par
volume, sans message d'erreur.

**Si tu lis `missing files: ['compose.prod.yaml']`** : tu n'es pas dans le
dossier d'installation. Cherche-le, puis recommence :

```bash
pwd && ls -la
find ~ -maxdepth 3 -name 'compose*.y*ml' 2>/dev/null
```

**Si le fichier n'existe nulle part, ou si `podman compose` est introuvable** :
les conteneurs et les volumes se suppriment très bien sans lui. Relève d'abord
leurs vrais noms — `podman-compose` 1.0.6 ne préfixe pas toujours les volumes
comme `podman compose` :

```bash
podman ps -a --filter name=dq- --format '{{.Names}}'
podman volume ls --format '{{.Name}}' | grep dq
```

puis, en reprenant exactement ces noms :

```bash
podman rm -f dq-app dq-postgres
podman volume rm dq-analyzer_dq-pgdata dq-analyzer_dq-storage dq-analyzer_dq-logs
```

Les noms sont écrits un par un, volontairement : aucune commande de ce guide ne
supprime « tous les conteneurs » ou « tous les volumes ».

### 12.6 — Vérifier que c'est bien parti

```bash
podman ps -a --filter name=dq-
podman volume ls --filter name=dq-analyzer
```

**Ce que tu dois voir** : deux listes vides (la ligne d'en-tête seule). Au
niveau A, les volumes sont toujours là : c'est normal et voulu.

**Si un conteneur résiste, ou réapparaît seul** :

```bash
podman rm -f dq-app          # le nom concerné
systemctl --user status podman-restart.service
```

Un conteneur qui revient tout seul est relancé par `podman-restart.service` :
fais la section 12.10 avant de réessayer.

### 12.7 — Vérifier qu'ÉCHO est intact

**Ne saute jamais cette section.** C'est la seule qui concerne les voisins, et
nous partageons leur portier.

```bash
curl -sk -I https://echo.ipscnam.ci/ | head -1
curl -sk -I https://socle.ipscnam.ci/ | head -1
systemctl is-active nginx
ss -tlnp | grep -E ':(80|443)\b'
```

**Ce que tu dois voir** : `HTTP/1.1 200` (ou `405`) pour les deux, `active`, et
les ports 80 et 443 toujours écoutés par le nginx du système.

| Symptôme | Cause | Solution |
| --- | --- | --- |
| ÉCHO ou Socle ne répond plus | nginx n'a pas redémarré : `include` vers un fichier supprimé | `sudo nginx -t` dit lequel ; remettre le fichier, ou retirer la ligne (section 12.9) |
| `nginx` est `inactive` | rechargement refusé, ou arrêt accidentel | `sudo nginx -t`, corriger, puis `sudo systemctl start nginx` |
| 80/443 ne sont plus écoutés | idem | même commande, puis prévenir la DSI |

### 12.8 — Supprimer l'image compilée (niveaux B et C)

```bash
podman images --filter reference='localhost/dq-analyzer*'
podman rmi localhost/dq-analyzer:prod
```

**Ce que tu dois voir** : une ou plusieurs lignes `Untagged` / `Deleted`.

L'image de base `postgres:17-alpine` peut servir à d'autres applications :
**laisse-la**. Elle n'occupe que quelques centaines de mégaoctets.

> **À ne jamais lancer sur cette machine** : `podman system prune`,
> `podman image prune -a`, `podman volume prune`, `podman rm -a`,
> `podman stop -a`. Ces commandes ignorent le nom de projet et emporteraient
> ÉCHO avec nous.

Au niveau B, tu t'arrêtes ici. Les sections 12.9 à 12.12 ne concernent que le C.

### 12.9 — Retirer le relais nginx (niveau C)

**L'ordre compte, et ce n'est pas celui qu'on croit** : on enlève d'abord la ligne
qui appelle le fichier, et seulement ensuite le fichier. Supprimer le fichier en
premier laisserait un `include` vers rien, et nginx **refuserait de démarrer** —
ÉCHO et Socle avec lui.

```bash
sudo cp /etc/nginx/conf.d/echo.ipscnam.ci.conf ~/echo.ipscnam.ci.conf.avant-retrait
sudo vi /etc/nginx/conf.d/echo.ipscnam.ci.conf     # retirer la ligne include .../dq.conf
sudo nginx -t
sudo systemctl reload nginx
sudo rm /etc/nginx/snippets/dq.conf
sudo nginx -t
```

**Ce que tu dois voir** : `test is successful` aux deux endroits, et rien d'autre
de modifié dans le vhost — `include` est la seule ligne à retirer.

Pense aussi à retirer la ligne du **dépôt d'ÉCHO** (`simulateur_V5`, fichier
`deploiement/nginx/echo.ipscnam.ci.conf`) : sinon le prochain déploiement d'ÉCHO
la remettrait, et nginx chercherait un fichier disparu.

**Aucun port à refermer dans le pare-feu** : on n'en a jamais ouvert. 8081 n'était
publié que sur la boucle locale.

### 12.10 — Désactiver le redémarrage automatique (niveau C)

```bash
systemctl --user list-units --type=service --state=running
```

**Regarde cette liste avant d'aller plus loin.** Si d'autres services
utilisateur que les nôtres y figurent, fais seulement :

```bash
systemctl --user disable --now podman-restart.service
```

et **ne touche pas au linger** : d'autres applications en dépendent peut-être.

Si la liste ne contient rien d'autre que nos conteneurs :

```bash
systemctl --user disable --now podman-restart.service
loginctl disable-linger "$USER"
loginctl show-user "$USER" --property=Linger
```

**Ce que tu dois voir** : `Linger=no`.

### 12.11 — Supprimer le dossier et les secrets (niveau C)

Le dossier contient une chose sensible : le `.env`, qui porte les mots de passe
de la base. On l'efface correctement, pas d'un simple `rm`.

```bash
cd ~
shred -u ~/dq-analyzer/.env 2>/dev/null || rm -f ~/dq-analyzer/.env
rm -rf ~/dq-analyzer
ls ~
```

**Ce que tu dois voir** : plus de `dq-analyzer` dans ton répertoire personnel.
`~/backups` reste : à conserver, ou à recopier avant de l'effacer.

**Plus de clé privée à détruire** : la version précédente de ce guide déposait une
copie du wildcard dans `deploy/certs/`, l'adossement au nginx du système l'a
rendue inutile. **Si tu avais déployé cette version-là**, la copie traîne peut-être
encore :

```bash
ls ~/dq-analyzer/deploy/certs 2>/dev/null && shred -u ~/dq-analyzer/deploy/certs/dq-analyzer.key
```

> **Ne supprime jamais** quoi que ce soit sous `/etc/nginx/` d'autre que
> `snippets/dq.conf` et notre ligne `include` (section 12.9). Les certificats qui
> s'y trouvent sont ceux de Socle, d'ÉCHO et de la messagerie, et le wildcard
> reste valable et en service.

Enfin, si tu étais passé par l'archive de l'étape 1 variante B :

```bash
rm -f ~/dq-analyzer.tar.gz
```

### 12.12 — Contrôle final (niveau C)

```bash
podman ps -a --filter name=dq-                              # vide
podman volume ls --filter name=dq-analyzer                  # vide
podman images --filter reference='localhost/dq-analyzer*'   # vide
ss -tlnp | grep ':8081' || echo "8081 libre"
ls /etc/nginx/snippets/dq.conf 2>/dev/null || echo "relais retiré"
grep dq.conf /etc/nginx/conf.d/echo.ipscnam.ci.conf || echo "include retiré"
ls ~/dq-analyzer 2>/dev/null || echo "dossier supprimé"
curl -sk -I https://echo.ipscnam.ci/ | head -1              # ÉCHO : 200 ou 405
```

**Ce que tu dois voir** : `8081 libre`, `relais retiré`, `include retiré`,
`dossier supprimé`, et ÉCHO qui répond.

La machine est dans l'état où tu l'as trouvée. Sur ton **poste**, pense à retirer
la ligne `10.10.4.165 echo.ipscnam.ci` de
`C:\Windows\System32\drivers\etc\hosts` si tu l'avais ajoutée à l'étape 10.

### 12.13 — Remettre en route après coup

| Niveau utilisé | Pour revenir | Ce que tu récupères |
| --- | --- | --- |
| **A** | `cd ~/dq-analyzer && podman compose -f compose.prod.yaml up -d` | tout, y compris les comptes |
| **B** | les étapes 6 à 9 | une installation vide ; l'admin est à recréer (étape 8) |
| **C** | le guide depuis l'étape 1 | une installation vide |

Après un niveau B, la base repart de zéro mais le `.env` est resté : **n'y
touche pas**. Sinon `db/init/01-create-database.sh` créera le compte applicatif
avec un mot de passe différent de celui que lit l'application, et `dq-app`
s'arrêtera sur `password authentication failed`.

Restaurer la sauvegarde de la section 12.2, une fois les conteneurs relancés :

```bash
podman exec -i dq-postgres pg_restore -U dqanalyzer -d dqanalyzer --clean < ~/backups/dq-avant-suppression-AAAA-MM-JJ.dump
```

### 12.14 — Si tu supprimes pour réparer

Un redéploiement complet est rarement la bonne réponse. Les pannes courantes se
règlent sans rien effacer :

| Symptôme | Ce qu'il faut faire, plutôt que tout supprimer |
| --- | --- |
| Alerte de certificat dans le navigateur | ce n'est plus notre affaire : le TLS est celui d'ÉCHO. `sudo nginx -t`, puis prévenir la DSI |
| `502 Bad Gateway` sur `/dq/` | `podman ps` : l'application est tombée. `podman restart dq-app` |
| `502` permanent | `sudo setsebool -P httpd_can_network_connect 1` |
| `404` sur `/dq/` alors qu'ÉCHO répond | la ligne `include` a disparu du vhost d'ÉCHO : revoir l'étape 5 |
| L'application ne répond plus | `podman logs dq-app \| tail -40`, puis `podman restart dq-app` |
| Code à jour à déployer | `git pull && podman compose -f compose.prod.yaml up -d --build app` |
| Mot de passe admin perdu | `./deploy/creer-admin.sh` : il propose la réinitialisation |

Le seul cas qui impose vraiment un `down -v` : un `.env` modifié **après** le
premier démarrage de PostgreSQL, qui provoque un `password authentication
failed` impossible à corriger autrement.

---

## Les trois échéances à ne pas oublier

1. **20 novembre 2026** — expiration du certificat wildcard. Il porte Socle, ÉCHO
   et la messagerie : son renouvellement est déjà un sujet pour la DSI, et nous
   n'avons plus rien à refaire ce jour-là — le TLS ne passe plus par nous.
2. **Le vhost d'ÉCHO est partagé** — toute évolution de
   `deploiement/nginx/echo.ipscnam.ci.conf` doit conserver la ligne
   `include /etc/nginx/snippets/dq.conf;`. À signaler à qui déploie ÉCHO.
3. **Aucun fichier réel d'assurés** avant les lots MFA-01 à MFA-07 et l'avis du
   RSSI (CDC §5.8, loi n° 2013-450). Jusque-là, recette et démonstrations sur
   fichiers de test, exclusivement.
