-- Comptes de démonstration, profil dev UNIQUEMENT (jamais chargé en prod).
-- Mot de passe des trois comptes : Admin2026!
-- Exécuté après chaque migration ; ON CONFLICT le rend rejouable sans erreur.
INSERT INTO app_user (login, full_name, email, password_hash, role)
VALUES ('admin',    'Administrateur (dev)', 'admin@dq.local',    '$2a$10$XPhL.KKvb1A6ymAWZf1CAe5ebUW.GgmlhxMECq9VXEnzWuJyu62r2', 'ADMIN'),
       ('analyste', 'Analyste (dev)',       'analyste@dq.local', '$2a$10$XPhL.KKvb1A6ymAWZf1CAe5ebUW.GgmlhxMECq9VXEnzWuJyu62r2', 'ANALYST'),
       ('lecteur',  'Lecteur (dev)',        'lecteur@dq.local',  '$2a$10$XPhL.KKvb1A6ymAWZf1CAe5ebUW.GgmlhxMECq9VXEnzWuJyu62r2', 'READER')
ON CONFLICT (login) DO NOTHING;
