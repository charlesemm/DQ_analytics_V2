-- V6 : une regle peut produire plus d'anomalies que ce qu'on accepte d'enregistrer (PERF-03).
-- Le score reste exact, puisqu'il vient des comptages ; le rapport doit juste dire que la liste
-- des anomalies est incomplete.
ALTER TABLE rule_result
    ADD COLUMN anomalies_truncated BOOLEAN NOT NULL DEFAULT FALSE;
