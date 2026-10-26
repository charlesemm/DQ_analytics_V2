# Déploiement de DQ-Analyzer sur `srv-gouv`

Mode opératoire condensé. Le détail et les explications sont à l'étape 14 de
`docs/partie_1.md` ; en cas de divergence, c'est le guide qui fait référence.

## Contexte du serveur

| Élément | Valeur |
| --- | --- |
| Hôte | `srv-gouv.ipscnam.ci` (10.10.4.165), accès SSH direct |
| Compte | `adm-mnguessan` |
| Système | RHEL / Rocky 8, SELinux actif, nginx 1.14.1 du système |
| Ports 80 et 443 | **occupés par ÉCHO · CNAM-CI — interdit d'y toucher** |
| Notre port | 8443 (libre), publié par notre propre nginx en conteneur |
| Certificat | wildcard `*.ipscnam.ci` GlobalSign, **expire le 20 novembre 2026** |

Règle d'or : aucune ressource partagée n'est modifiée. Rien n'est installé sur la
machine (ni Java, ni Maven, ni PostgreSQL), `/etc/nginx/` n'est jamais touché, le
nginx du système n'est jamais redémarré. Tout vit dans Podman et se défait avec
`podman compose down`.

## Fichiers de ce dossier

| Fichier | Rôle |
| --- | --- |
| `../compose.prod.yaml` | les trois conteneurs : `postgres`, `app`, `proxy` (seul `proxy` publie un port) |
| `nginx.conf` | notre nginx, écoute en 8443 TLS et relaie vers `app:8080` |
| `certs/` | copies du certificat et de la clé — **jamais dans Git** (`.gitignore`) |
| `../.env.example` | modèle du `.env` de production (secrets, non versionné) |

## Première installation

```bash
ssh adm-mnguessan@10.10.4.165

# 1. Prérequis (aucune installation : si podman manque, demander à l'administrateur)
podman --version          # attendu : 4.4 ou plus
df -h                     # attendu : au moins 50 Go libres
free -g                   # attendu : 8 Go de RAM

# 2. Le projet, dans le dossier personnel (pas /opt : pas de sudo, pas de mélange
#    avec les applications des autres)
mkdir -p ~/dq-analyzer && cd ~/dq-analyzer
git clone https://github.com/charlesemm/DQ_analytics_V2.git .

# 3. Le certificat : on travaille sur NOTRE copie. Monter l'original avec ":Z" le
#    réétiquetterait (SELinux) et casserait ÉCHO.
sudo grep -r ssl_certificate /etc/nginx/ | grep -v "#"   # repérer les chemins d'ÉCHO
mkdir -p deploy/certs
sudo cp <chemin-du-certificat> deploy/certs/dq-analyzer.crt
sudo cp <chemin-de-la-clé>    deploy/certs/dq-analyzer.key
sudo chown "$USER": deploy/certs/*
chmod 600 deploy/certs/dq-analyzer.key

# 4. Les secrets
cp .env.example .env
openssl rand -base64 24    # une valeur pour POSTGRES_PASSWORD
openssl rand -base64 24    # une autre pour DQ_DB_PASSWORD
vi .env                    # renseigner les deux, et le relais SMTP s'il est connu
chmod 600 .env

# 5. Le port, puis le lancement (plusieurs minutes : l'image se construit sur place)
sudo firewall-cmd --add-port=8443/tcp --permanent && sudo firewall-cmd --reload
podman compose -f compose.prod.yaml up -d --build
podman ps                  # trois conteneurs "Up" ; dq-postgres et dq-app "healthy"

# 6. Survivre à la déconnexion et au redémarrage du serveur
loginctl enable-linger "$USER"
systemctl --user enable --now podman-restart.service
```

### Premier administrateur

En profil `prod`, aucun compte de démonstration n'est chargé : `app_user` est vide.
Une fois Flyway passé :

```bash
podman run --rm docker.io/library/httpd:2.4-alpine htpasswd -nbBC 12 "" 'MotDePasseChoisi'
# affiche  :$2y$12$....   → garder tout ce qui suit le « : »
podman exec -it dq-postgres psql -U dqanalyzer -d dqanalyzer
```

```sql
INSERT INTO app_user (login, full_name, email, password_hash, role)
VALUES ('mnguessan', 'Mathieu Charles N''GUESSAN', 'prenom.nom@ipscnam.ci', '$2y$12$...', 'ADMIN');
```

## Vérifier — les voisins d'abord, nous ensuite

```bash
curl -sk -I https://srv-gouv.ipscnam.ci/ | head -1          # ÉCHO : 200 ou 405
systemctl is-active nginx                                    # active
podman exec dq-app curl -s localhost:8080/actuator/health    # {"status":"UP"}
curl -sI https://srv-gouv.ipscnam.ci:8443/ | head -1         # 200, volontairement sans -k
```

Puis depuis un poste : https://srv-gouv.ipscnam.ci:8443 affiche la page de connexion
sans alerte de sécurité.

| Symptôme | Cause | Solution |
| --- | --- | --- |
| `502 Bad Gateway` | SELinux interdit la connexion réseau sortante | `sudo setsebool -P httpd_can_network_connect 1` |
| ÉCHO tombe après le déploiement | un montage `:Z` a réétiqueté son certificat | `sudo restorecon -Rv <chemin-du-certificat-d-origine>` |
| Connexion refusée sur 8443 | pare-feu | revoir `firewall-cmd` ci-dessus |
| Disque qui se remplit | DuckDB consomme 3 × la taille du fichier par analyse | `df -h`, purger le volume `dq-storage` |

## Exploitation

```bash
# Mise à jour : Flyway applique seul les nouvelles migrations au redémarrage.
# Le redémarrage du proxy n'est pas optionnel : nginx résout le nom "app" une seule
# fois, au démarrage. Le conteneur recréé peut changer d'IP → 502 sans ce restart.
git pull && podman compose -f compose.prod.yaml up -d --build app && podman restart dq-proxy

# Sauvegarde de la base (à planifier chaque nuit, timer systemd utilisateur)
mkdir -p ~/backups
podman exec dq-postgres pg_dump -U dqanalyzer -Fc dqanalyzer > ~/backups/dq-$(date +%F).dump

# Journaux
podman logs -f dq-app

# Annuler
podman compose -f compose.prod.yaml down      # arrêter, garder la base
podman compose -f compose.prod.yaml down -v   # tout effacer, base et fichiers importés compris
sudo firewall-cmd --remove-port=8443/tcp --permanent && sudo firewall-cmd --reload
```

Copier les sauvegardes hors du serveur : un dump sur le même disque ne protège pas
d'une panne de ce disque, et il occupe l'espace des autres applications.

## À ne pas oublier

- **20 novembre 2026** : expiration du certificat. Après renouvellement, refaire
  l'étape 3 puis `podman restart dq-proxy`.
- **URL sans numéro de port** : demander à la DSI un enregistrement A
  `dq.ipscnam.ci` → 10.10.4.165 (le wildcard le couvre déjà). En attendant, une
  ligne dans le fichier `hosts` d'un poste de démonstration suffit.
- **Aucun fichier réel d'assurés** avant les lots MFA-01 à MFA-07 et l'avis du
  RSSI (CDC §5.8, loi n° 2013-450) : recette et démonstrations sur fichiers de test.
