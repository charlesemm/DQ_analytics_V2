package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.XmlSettings;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lecture d'un fichier XML en flux : l'élément qui se répète vaut une ligne, ses sous-éléments et ses
 * attributs valent les colonnes (CDC §5.1 bis). Le fichier n'est jamais chargé en mémoire (PERF-03).
 *
 * <p>Mise à plat, pour que les règles voient un tableau :
 *
 * <ul>
 *   <li>un sous-élément imbriqué donne une colonne {@code adresse.ville} ;
 *   <li>un attribut donne une colonne préfixée d'une arobase : {@code @id}, {@code adresse.@type} ;
 *   <li>un sous-élément qui apparaît plusieurs fois dans la même ligne donne une seule colonne, ses
 *       valeurs séparées par « | » : aucune donnée n'est perdue en silence.
 * </ul>
 */
public final class XmlRowSource extends RecordRowSource {

    /** Sépare les valeurs d'un sous-élément répété dans la même ligne. */
    private static final String REPEATED_SEPARATOR = " | ";

    private static final String ATTRIBUTE_PREFIX = "@";

    private final CountingInputStream input;
    private final XMLStreamReader xml;
    private final long fileSize;
    private final String rowElement;

    /** Vrai quand la détection a déjà placé le lecteur sur le premier élément répété (IMP-07). */
    private boolean positionedOnRow;

    private XmlRowSource(CountingInputStream input, XMLStreamReader xml, long fileSize, String rowElement) {
        this.input = input;
        this.xml = xml;
        this.fileSize = fileSize;
        this.rowElement = rowElement;
    }

    /**
     * Ouvre le fichier et lit son premier enregistrement, qui donne les colonnes. Si l'élément répété
     * n'est pas précisé, c'est le premier élément sous la racine (IMP-07).
     *
     * @throws ImportRejectedException si le fichier est illisible ou ne contient aucun enregistrement
     */
    public static XmlRowSource open(Path file, XmlSettings settings) {
        CountingInputStream input = null;
        XMLStreamReader xml = null;
        try {
            long fileSize = Files.size(file);
            input = new CountingInputStream(Files.newInputStream(file));
            xml = XmlReaders.create(input);
            String element = settings.elementName();
            boolean detected = false;
            if (element.isEmpty()) {
                element = firstChildOfRoot(xml);
                detected = true;
            }
            var source = new XmlRowSource(input, xml, fileSize, element);
            source.positionedOnRow = detected;
            source.readFirstRecord();
            return source;
        } catch (ImportRejectedException e) {
            closeQuietly(xml, input);
            throw e;
        } catch (IOException | XMLStreamException | RuntimeException e) {
            closeQuietly(xml, input);
            throw new ImportRejectedException("import.error.fichier-illisible");
        }
    }

    /** Élément répété retenu : celui demandé, ou celui que la détection a trouvé. */
    public String rowElement() {
        return rowElement;
    }

    @Override
    protected Map<String, String> nextRecord() {
        try {
            if (positionedOnRow) {
                positionedOnRow = false;
                return readRecord();
            }
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT && rowElement.equals(xml.getLocalName())) {
                    return readRecord();
                }
            }
            return null;
        } catch (XMLStreamException e) {
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
        closeQuietly(xml, input);
    }

    /** Lit l'élément répété courant et le met à plat. */
    private Map<String, String> readRecord() throws XMLStreamException {
        var values = new LinkedHashMap<String, String>();
        readElement("", values);
        return values;
    }

    /**
     * Lit l'élément courant et range ses valeurs dans {@code values}.
     *
     * @param path chemin de l'élément depuis la ligne, vide pour l'élément répété lui-même
     * @return le texte de l'élément s'il n'a pas de sous-élément, {@code null} sinon
     */
    private String readElement(String path, Map<String, String> values) throws XMLStreamException {
        for (int index = 0; index < xml.getAttributeCount(); index++) {
            String name = xml.getAttributeLocalName(index);
            put(
                    values,
                    path.isEmpty() ? ATTRIBUTE_PREFIX + name : path + "." + ATTRIBUTE_PREFIX + name,
                    xml.getAttributeValue(index));
        }
        var text = new StringBuilder();
        boolean hasChildren = false;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                hasChildren = true;
                String child = xml.getLocalName();
                String childPath = path.isEmpty() ? child : path + "." + child;
                String childText = readElement(childPath, values);
                if (childText != null) {
                    put(values, childPath, childText);
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(xml.getText());
            }
        }
        String content = text.toString().trim();
        return hasChildren || content.isEmpty() ? null : content;
    }

    /** Deux fois la même colonne dans une ligne : les valeurs se suivent au lieu de s'écraser. */
    private static void put(Map<String, String> values, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        values.merge(name, value.trim(), (first, next) -> first + REPEATED_SEPARATOR + next);
    }

    /** Premier élément sous la racine : à défaut d'indication, c'est lui qui se répète. */
    private static String firstChildOfRoot(XMLStreamReader xml) throws XMLStreamException {
        int depth = 0;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (depth == 2) {
                    return xml.getLocalName();
                }
            }
        }
        throw new ImportRejectedException("import.error.aucun-enregistrement");
    }

    private static void closeQuietly(XMLStreamReader xml, Closeable input) {
        if (xml != null) {
            try {
                xml.close();
            } catch (XMLStreamException e) {
                // Lecture terminée : rien de plus à tenter.
            }
        }
        if (input != null) {
            try {
                input.close();
            } catch (IOException e) {
                // Fichier ouvert en lecture seule : rien de plus à tenter.
            }
        }
    }
}
