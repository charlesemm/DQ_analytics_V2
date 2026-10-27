-- Envoi d'un fichier par morceaux, avec reprise après une coupure réseau (IMP-01, PERF-02).
-- Les morceaux eux-mêmes sont des fichiers sur le disque, dans dq.storage.path/uploads/<id>/ :
-- ce sont eux qui disent ce qui est déjà arrivé, et ils survivent donc à un redémarrage du serveur.
-- Cette table ne porte que ce qu'il faut pour reprendre : à qui appartient l'envoi, de quel fichier
-- il s'agit, et en combien de morceaux il est découpé.
CREATE TABLE upload_session (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name    VARCHAR(500) NOT NULL,
    total_size   BIGINT,
    chunk_size   INTEGER      NOT NULL,
    created_by   BIGINT       NOT NULL REFERENCES app_user (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

-- Pour retrouver les envois abandonnés, que la purge effacera avec leurs morceaux (SEC-04).
CREATE INDEX idx_upload_session_created ON upload_session (created_at) WHERE completed_at IS NULL;

COMMENT ON COLUMN upload_session.total_size IS 'Taille annoncée par le navigateur, indicative : seule la taille réellement reçue est contrôlée';
COMMENT ON COLUMN upload_session.completed_at IS 'Date d''assemblage ; NULL tant que l''envoi est en cours ou abandonné';
