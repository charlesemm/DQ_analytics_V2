# Guide de déploiement de DQ-Analyzer sur `srv-gouv`

Ce guide se suit du haut vers le bas, une étape à la fois. Chaque étape donne la
commande, **ce que tu dois voir**, et quoi faire si ça échoue. Les explications
longues sont à l'étape 14 de `partie_1.md` ; ici, on exécute.

Deux terminaux sont utilisés. Ils ne sont jamais interchangeables :

| Repère | Où | Quoi |
| --- | --- | --- |
| **Poste** | PowerShell, dans `C:\Users\charles.nguessan\DQ\DQ_analytics` | envoi du code et des certificats |
| **Serveur** | ta session SSH `adm-mnguessan@10.10.4.165` | tout le reste |

Rappel de la contrainte qui explique tout le reste : le serveur héberge déjà
**ÉCHO · CNAM-CI** sur les ports 80 et 443. On ne touche ni à ces ports, ni à
`/etc/nginx/`, ni au nginx du système, et on n'installe rien sur la machine.
DQ-Analyzer arrive avec son propre nginx en conteneur, sur le port **8443**.

---

## Étape 0 — Avant de commencer

À vérifier une fois, sur ton **poste** :

```powershell
git log --oneline -1          # doit afficher le commit "feat(deploiement)..."
dir deploy\certs              # doit lister dq-analyzer.crt et dq-analyzer.key
ssh adm-mnguessan@10.10.4.165 "hostname && podman --version"
```

**Ce que tu dois voir** : le nom du serveur, puis `podman 4.x` ou plus.

**Si `podman` est introuvable** : arrête-toi ici. L'installer modifie la machine
partagée ; c'est la seule action de ce guide qui demande l'accord de
l'administrateur du serveur.

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
sur le serveur — d'où l'étape 2 pour les certificats.

Le dossier d'installation est `~/dq-analyzer`, dans ton répertoire personnel, et
non `/opt` : pas de `sudo`, et le projet ne se mélange pas à ceux des autres.

---

## Étape 2 — Envoyer les certificats

Les certificats ne sont **jamais** dans Git : ils voyagent à part. Tu as déjà la
copie locale, donc tu n'as pas besoin de toucher aux fichiers d'ÉCHO sur le serveur.

Sur le **serveur** :

```bash
mkdir -p ~/dq-analyzer/deploy/certs
```

Sur ton **poste** :

```powershell
scp deploy\certs\dq-analyzer.crt deploy\certs\dq-analyzer.key adm-mnguessan@10.10.4.165:~/dq-analyzer/deploy/certs/
```

Sur le **serveur**, refermer la clé :

```bash
chmod 600 ~/dq-analyzer/deploy/certs/dq-analyzer.key
ls -l ~/dq-analyzer/deploy/certs/
openssl x509 -in ~/dq-analyzer/deploy/certs/dq-analyzer.crt -noout -subject -dates
```

**Ce que tu dois voir** : `subject=CN=*.ipscnam.ci` et
`notAfter=Nov 20 11:45:48 2026 GMT`.

**Le fichier `.crt` contient deux certificats** (le tien, puis l'intermédiaire
GlobalSign) : c'est volontaire, c'est ce qui évite l'avertissement « autorité
inconnue » dans les navigateurs.

**Ne jamais** monter dans un conteneur le certificat d'ÉCHO situé sous
`/etc/nginx/` : l'option `:Z` de Podman le réétiquetterait (SELinux) et **ÉCHO
tomberait**. On travaille sur notre copie, uniquement.

---

## Étape 3 — Vérifier la place et le port

Sur le **serveur** :

```bash
df -h /home                      # au moins 50 Go libres
free -g                          # au moins 8 Go de RAM
ss -tlnp | grep -E ':(8443|8080)' || echo "8443 et 8080 libres"
```

**Ce que tu dois voir** : `8443 et 8080 libres`.

**Si 8443 est déjà pris** : quelqu'un s'y est installé entre-temps. Choisis 9443
(libre d'après le relevé) et change les trois endroits qui le mentionnent :
`listen` dans `deploy/nginx.conf`, le `ports:` du service `proxy` dans
`compose.prod.yaml`, et la règle de pare-feu de l'étape 5.

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

**`DQ_MAIL_HOST`** : laisse la valeur par défaut pour l'instant. L'application
démarrera normalement ; seuls les codes 2FA par e-mail ne partiront pas. Quand
tu auras l'adresse du relais (le certificat mentionne `mail.ipscnam.ci`),
renseigne-la et relance l'application — voir l'étape 11.

---

## Étape 5 — Ouvrir le port dans le pare-feu

Sur le **serveur** :

```bash
sudo firewall-cmd --add-port=8443/tcp --permanent && sudo firewall-cmd --reload
sudo firewall-cmd --list-ports
```

**Ce que tu dois voir** : `8443/tcp` dans la liste, à côté des ports déjà
ouverts par les autres applications. On **ajoute** une règle, on n'en retire
aucune.

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

**Ce que tu dois voir** : trois conteneurs `Up` — `dq-postgres` et `dq-app`
marqués `healthy`, `dq-proxy` sans mention. `dq-app` peut rester `starting`
pendant 90 secondes : c'est le temps laissé à Spring et à Flyway.

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
curl -sk -I https://srv-gouv.ipscnam.ci/ | head -1          # ÉCHO : 200 ou 405
systemctl is-active nginx                                    # active
podman exec dq-app curl -s localhost:8080/actuator/health    # {"status":"UP"}
curl -sI https://srv-gouv.ipscnam.ci:8443/ | head -1         # 200 ou 302
```

La dernière commande est volontairement **sans `-k`** : si elle réussit, le
certificat est pleinement validé, donc aucun navigateur n'affichera d'alerte.

| Symptôme | Cause | Solution |
| --- | --- | --- |
| ÉCHO ne répond plus | un montage a réétiqueté son certificat | `sudo restorecon -Rv /etc/nginx` puis `sudo systemctl reload nginx` |
| `502 Bad Gateway` | SELinux bloque la connexion sortante | `sudo setsebool -P httpd_can_network_connect 1` |
| `502` après une mise à jour | nginx garde l'ancienne IP du conteneur | `podman restart dq-proxy` |
| `curl: (7) Failed to connect` sur 8443 | pare-feu | revoir l'étape 5 |
| Erreur de certificat sans `-k` | chaîne incomplète | revoir l'étape 2 (le `.crt` doit contenir deux certificats) |

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

Ouvre **https://srv-gouv.ipscnam.ci:8443**

**Ce que tu dois voir** : la page de connexion, le cadenas fermé, aucune alerte
de sécurité. Connecte-toi avec le compte de l'étape 8.

**Si le navigateur ne trouve pas le nom** : ajoute `10.10.4.165 srv-gouv.ipscnam.ci`
dans `C:\Windows\System32\drivers\etc\hosts` (à ouvrir en administrateur).

**Le numéro de port dans l'URL** convient à une recette, pas à un usage
quotidien. Pour s'en débarrasser, une seule demande à faire à la DSI : un
enregistrement A `dq.ipscnam.ci` vers 10.10.4.165 — le wildcard le couvre déjà.

---

## Étape 11 — Vivre avec

### Mettre à jour l'application

```bash
cd ~/dq-analyzer
git pull                                                     # ou refaire l'étape 1 variante B
podman compose -f compose.prod.yaml up -d --build app
podman restart dq-proxy                                      # obligatoire, voir ci-dessous
```

`podman restart dq-proxy` n'est pas optionnel : nginx résout le nom `app` une
seule fois, au démarrage. Le conteneur reconstruit peut recevoir une autre IP, et
le proxy répondrait `502` en continuant d'interroger l'ancienne.

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
podman stats --no-stream         # mémoire et processeur des trois conteneurs
df -h /home                      # surveiller le disque (DuckDB)
```

### Tout arrêter, tout annuler

```bash
podman compose -f compose.prod.yaml down       # arrêter, garder la base
podman compose -f compose.prod.yaml down -v    # tout effacer, base et fichiers importés
sudo firewall-cmd --remove-port=8443/tcp --permanent && sudo firewall-cmd --reload
```

Après la première commande, la machine est exactement dans l'état où tu l'as
trouvée du point de vue des autres applications : elles n'ont jamais été
touchées. C'est tout l'intérêt d'avoir refusé de modifier `/etc/nginx/`.

---

## Les trois échéances à ne pas oublier

1. **20 novembre 2026** — expiration du certificat. Il porte aussi ÉCHO et la
   messagerie, donc son renouvellement est déjà un sujet pour la DSI. Quand il
   sera renouvelé : refaire l'étape 2, puis `podman restart dq-proxy`.
2. **URL sans port** — demander `dq.ipscnam.ci` → 10.10.4.165 à la DSI.
3. **Aucun fichier réel d'assurés** avant les lots MFA-01 à MFA-07 et l'avis du
   RSSI (CDC §5.8, loi n° 2013-450). Jusque-là, recette et démonstrations sur
   fichiers de test, exclusivement.
