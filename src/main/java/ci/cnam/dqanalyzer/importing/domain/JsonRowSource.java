package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.JsonSettings;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/**
 * Lecture d'un fichier JSON en flux : un objet vaut une ligne, ses propriétés valent les colonnes
 * (CDC §5.1 bis). Le fichier n'est jamais chargé en mémoire (PERF-03), qu'il contienne un tableau de
 * cent mille objets ou un objet par ligne (.jsonl, .ndjson).
 *
 * <p>Mise à plat, pour que les règles voient un tableau :
 *
 * <ul>
 *   <li>un objet imbriqué donne une colonne {@code adresse.ville} ;
 *   <li>un tableau de valeurs donne une colonne unique, valeurs séparées par « | » ;
 *   <li>un tableau d'objets donne une colonne par propriété et par rang : {@code enfants.1.nom}.
 * </ul>
 */
public final class JsonRowSource extends RecordRowSource {

    /** Sépare les valeurs d'un tableau dans la même ligne. */
    private static final String REPEATED_SEPARATOR = " | ";

    private final CountingInputStream input;
    private final JsonParser parser;
    private final long fileSize;

    /** Vrai si les objets sont dans un tableau : la fin du tableau est alors la fin du fichier. */
    private final boolean insideArray;

    /** Vrai quand le lecteur est déjà placé sur un objet, qui n'a donc pas à être cherché. */
    private boolean positionedOnObject;

    private JsonRowSource(CountingInputStream input, JsonParser parser, long fileSize, boolean insideArray) {
        this.input = input;
        this.parser = parser;
        this.fileSize = fileSize;
        this.insideArray = insideArray;
    }

    /**
     * Ouvre le fichier, se place sur le tableau d'objets demandé et lit son premier objet, qui donne
     * les colonnes.
     *
     * @throws ImportRejectedException si le fichier est illisible ou ne contient aucun objet
     */
    public static JsonRowSource open(Path file, JsonSettings settings) {
        CountingInputStream input = null;
        JsonParser parser = null;
        try {
            long fileSize = Files.size(file);
            input = new CountingInputStream(Files.newInputStream(file));
            parser = new JsonFactory().createParser(input);
            var start = moveToObjects(parser, settings.arrayPath());
            var source = new JsonRowSource(input, parser, fileSize, start.insideArray());
            source.positionedOnObject = start.positionedOnObject();
            source.readFirstRecord();
            return source;
        } catch (ImportRejectedException e) {
            closeQuietly(parser, input);
            throw e;
        } catch (IOException | RuntimeException e) {
            closeQuietly(parser, input);
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    @Override
    protected Map<String, String> nextRecord() {
        try {
            if (positionedOnObject) {
                positionedOnObject = false;
            } else {
                JsonToken token = parser.nextToken();
                while (token != null && token != JsonToken.START_OBJECT) {
                    if (insideArray && token == JsonToken.END_ARRAY) {
                        return null;
                    }
                    token = parser.nextToken();
                }
                if (token == null) {
                    return null;
                }
            }
            var values = new LinkedHashMap<String, String>();
            readObject("", values);
            return values;
        } catch (JacksonException e) {
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    @Override
    public int percent() {
        if (fileSize <= 0) {
            return MAX_PERCENT_BEFORE_END;
        }
        return (int) Math.min(MAX_PERCENT_BEFORE_END, input.bytesRead() * 100 / fileSize);
    }

    @Override
    public void close() throws IOException {
        closeQuietly(parser, input);
    }

    /**
     * Se place sur le premier objet à lire, ou juste avant lui.
     *
     * @param arrayPath chemin du tableau, vide pour le tableau principal ou un objet par ligne
     */
    private static Start moveToObjects(JsonParser parser, String arrayPath) {
        if (!arrayPath.isEmpty()) {
            for (String segment : arrayPath.split("/")) {
                if (segment.isBlank()) {
                    continue;
                }
                if (!moveToField(parser, segment)) {
                    throw new ImportRejectedException("import.error.chemin-introuvable", arrayPath);
                }
            }
        }
        return switch (parser.nextToken()) {
            case START_ARRAY -> new Start(true, false);
            // Un objet par ligne (.jsonl) ou un objet unique : la lecture reprend sur cet objet.
            case START_OBJECT -> new Start(false, true);
            case null, default -> throw new ImportRejectedException("import.error.aucun-enregistrement");
        };
    }

    /** Où la lecture commence : dans un tableau d'objets, ou directement sur un objet. */
    private record Start(boolean insideArray, boolean positionedOnObject) {}

    private static boolean moveToField(JsonParser parser, String name) {
        JsonToken token = parser.nextToken();
        while (token != null) {
            if (token == JsonToken.PROPERTY_NAME && name.equals(parser.currentName())) {
                return true;
            }
            token = parser.nextToken();
        }
        return false;
    }

    /** Lit l'objet courant, déjà ouvert, et range ses propriétés à plat. */
    private void readObject(String prefix, Map<String, String> values) {
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.END_OBJECT) {
            if (token != JsonToken.PROPERTY_NAME) {
                continue;
            }
            String name = prefix + parser.currentName();
            switch (parser.nextToken()) {
                case START_OBJECT -> readObject(name + ".", values);
                case START_ARRAY -> readArray(name, values);
                case VALUE_NULL -> {
                    // Une propriété nulle vaut « non renseigné » : rien à enregistrer.
                }
                default -> put(values, name, parser.getValueAsString());
            }
        }
    }

    /** Lit le tableau courant : valeurs mises bout à bout, objets rangés par leur rang. */
    private void readArray(String name, Map<String, String> values) {
        JsonToken token;
        int rank = 0;
        while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
            rank++;
            switch (token) {
                case START_OBJECT -> readObject(name + "." + rank + ".", values);
                case START_ARRAY -> readArray(name + "." + rank, values);
                case VALUE_NULL -> {
                    // Valeur absente du tableau : rien à enregistrer.
                }
                default -> put(values, name, parser.getValueAsString());
            }
        }
    }

    private static void put(Map<String, String> values, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        values.merge(name, value.trim(), (first, next) -> first + REPEATED_SEPARATOR + next);
    }

    private static void closeQuietly(Closeable parser, Closeable input) {
        for (Closeable stream : new Closeable[] {parser, input}) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException | RuntimeException e) {
                    // Fichier ouvert en lecture seule : rien de plus à tenter.
                }
            }
        }
    }
}
