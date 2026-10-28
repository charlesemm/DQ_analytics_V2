-- V5 : propriétaire métier des règles (gouvernance fédérée), profil des fichiers en cache,
-- numérotation lisible des règles (REG-01, REG-04, PRF-01).

-- Chaque règle appartient à une direction métier, qui la valide (modèle de gouvernance fédéré).
-- Le CDC exclut le circuit d'approbation en V1 : on trace la validation, sans workflow.
ALTER TABLE dq_rule
    ADD COLUMN business_owner VARCHAR(200),
    ADD COLUMN validated_by   VARCHAR(200),
    ADD COLUMN validated_on   DATE,
    ADD COLUMN change_comment VARCHAR(500);

CREATE INDEX idx_dq_rule_business_owner ON dq_rule (business_owner);

-- Numéros de règle lisibles : R-0001, R-0002… Une séquence reste sûre si deux analystes
-- créent une règle au même instant.
CREATE SEQUENCE dq_rule_code_seq START 1;

-- Profil des colonnes calculé après l'import, gardé pour ne pas le recalculer à chaque affichage.
ALTER TABLE source_file
    ADD COLUMN profile JSONB;
