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
