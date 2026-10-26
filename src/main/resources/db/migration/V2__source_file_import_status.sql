-- Suivi du chargement d'un fichier importé (IMP-06, PERF-03).
-- Le fichier source est enregistré dès son arrivée ; sa lecture vers DuckDB se fait ensuite en
-- tâche de fond, d'où un statut et un avancement propres à l'import, distincts de ceux d'une analyse.
ALTER TABLE source_file
    ADD COLUMN import_status    VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (import_status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    ADD COLUMN import_progress  INTEGER     NOT NULL DEFAULT 0
        CHECK (import_progress BETWEEN 0 AND 100),
    ADD COLUMN import_error     VARCHAR(1000),
    ADD COLUMN ragged_row_count BIGINT,
    -- Base analytique et table produites par l'import ; le schéma de la table, lui, n'est pas
    -- recopié ici : DuckDB en est la seule source de vérité.
    ADD COLUMN duckdb_path      VARCHAR(1000),
    ADD COLUMN table_name       VARCHAR(63);

CREATE INDEX idx_source_file_import_status ON source_file (import_status);
