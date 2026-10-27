-- Archive .zip ne contenant qu'un seul fichier (IMP-09).
-- Le fichier conservé reste celui que l'analyste a envoyé, avec sa taille et son empreinte (IMP-06) :
-- c'est l'archive. Le fichier analysé est celui qu'elle contient, extrait à la réception.
ALTER TABLE source_file
    ADD COLUMN extracted_path VARCHAR(1000),
    ADD COLUMN extracted_name VARCHAR(500);

COMMENT ON COLUMN source_file.extracted_path IS 'Fichier extrait de l''archive, lu à la place de storage_path ; NULL si le fichier reçu n''était pas une archive';
COMMENT ON COLUMN source_file.extracted_name IS 'Nom du fichier à l''intérieur de l''archive, qui a donné son format';
