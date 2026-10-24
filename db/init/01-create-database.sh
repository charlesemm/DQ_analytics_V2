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
