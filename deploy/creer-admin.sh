#!/usr/bin/env bash
# Crée un compte administrateur DQ-Analyzer (étape 8 du guide de déploiement).
# En profil prod aucun compte n'est chargé : la table app_user est vide au premier démarrage.
# À lancer sur le serveur, depuis la racine du dépôt : ./deploy/creer-admin.sh
set -euo pipefail

PG_CONTAINER=dq-postgres
DB_USER=dqanalyzer
DB_NAME=dqanalyzer
# Doit rester aligné sur le BCryptPasswordEncoder(12) de SecurityConfig.
BCRYPT_COST=12

psql_run() { podman exec -i "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"; }

podman ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER" || {
    echo "Erreur : le conteneur $PG_CONTAINER ne tourne pas." >&2
    echo "Lance d'abord : podman compose -f compose.prod.yaml up -d" >&2
    exit 1
}

read -rp "Identifiant (ex. mnguessan) : " login
[[ -n ${login// /} ]] || { echo "Erreur : identifiant vide." >&2; exit 1; }

# L'application cherche le login sans tenir compte de la casse : on vérifie de même.
deja_pris=$(psql_run -tA -v l="$login" <<'SQL'
SELECT count(*) FROM app_user WHERE lower(login) = lower(:'l');
SQL
)

mode=creation
if [[ $deja_pris != 0 ]]; then
    echo "Le compte « $login » existe déjà."
    read -rp "Réinitialiser son mot de passe ? [o/N] " reponse
    [[ ${reponse,,} == o* ]] || { echo "Abandon, rien n'a été modifié."; exit 0; }
    mode=reinitialisation
else
    read -rp "Nom complet (ex. Mathieu Charles N'GUESSAN) : " nom_complet
    [[ -n ${nom_complet// /} ]] || { echo "Erreur : nom complet vide." >&2; exit 1; }

    read -rp "Adresse e-mail : " email
    [[ $email == *@*.* ]] || { echo "Erreur : adresse e-mail invalide." >&2; exit 1; }

    read -rp "Rôle [ADMIN/ANALYST/READER] (ADMIN par défaut) : " role
    role=${role:-ADMIN}
    role=${role^^}
    case $role in
        ADMIN | ANALYST | READER) ;;
        *) echo "Erreur : rôle inconnu « $role »." >&2; exit 1 ;;
    esac
fi

# -s : la saisie n'apparaît pas à l'écran et ne passe pas par la ligne de commande.
read -rsp "Mot de passe : " mot_de_passe && echo
read -rsp "Confirmation  : " confirmation && echo
[[ $mot_de_passe == "$confirmation" ]] || { echo "Erreur : les deux saisies diffèrent." >&2; exit 1; }
((${#mot_de_passe} >= 12)) || { echo "Erreur : 12 caractères minimum." >&2; exit 1; }

# htpasswd -i lit le mot de passe sur l'entrée standard : il n'apparaît jamais dans ps ni dans l'historique.
empreinte=$(printf '%s\n' "$mot_de_passe" \
    | podman run --rm -i docker.io/library/httpd:2.4-alpine htpasswd -niBC "$BCRYPT_COST" "" \
    | cut -d: -f2-)
[[ $empreinte == \$2y\$* ]] || { echo "Erreur : empreinte BCrypt inattendue." >&2; exit 1; }

# :'variable' est substitué par psql en littéral SQL correctement échappé :
# les apostrophes des noms propres (N'GUESSAN) sont gérées sans intervention.
if [[ $mode == creation ]]; then
    psql_run -v l="$login" -v n="$nom_complet" -v e="$email" -v h="$empreinte" -v r="$role" <<'SQL'
INSERT INTO app_user (login, full_name, email, password_hash, role)
VALUES (:'l', :'n', :'e', :'h', :'r');
SQL
else
    psql_run -v l="$login" -v h="$empreinte" <<'SQL'
UPDATE app_user SET password_hash = :'h', failed_mfa_attempts = 0, locked_until = NULL, updated_at = now()
WHERE lower(login) = lower(:'l');
SQL
fi

echo
echo "Compte enregistré :"
psql_run -v l="$login" <<'SQL'
SELECT login, full_name, role, active FROM app_user WHERE lower(login) = lower(:'l');
SQL
echo "Connexion : https://srv-gouv.ipscnam.ci:8443"
