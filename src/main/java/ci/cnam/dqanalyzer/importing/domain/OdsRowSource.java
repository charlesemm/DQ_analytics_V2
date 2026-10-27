package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lecture d'un classeur OpenDocument (.ods) en flux. Un .ods est une archive zip dont
 * {@code content.xml} porte les tableaux : il se lit donc comme du XML, avec le lecteur du JDK, sans
 * bibliothèque supplémentaire et sans charger le classeur en mémoire (PERF-03).
 *
 * <p>La valeur retenue est le texte affiché par le tableur ({@code text:p}) : une date s'écrit
 * « 31/12/2024 », un booléen « VRAI ». Les valeurs brutes des attributs {@code office:*} ne servent
 * que lorsqu'une cellule n'a pas de texte affiché.
 *
 * <p>Deux particularités du format sont traitées ici : les lignes et les cellules identiques sont
 * écrites une seule fois avec un facteur de répétition, et une feuille se termine toujours par un
 * millier de cellules vides répétées, qu'il ne faut pas prendre pour des colonnes.
 */
public final class OdsRowSource extends SheetRowSource {

    private static final String CONTENT_ENTRY = "content.xml";
    private static final String TABLE = "table";
    private static final String TABLE_ROW = "table-row";
    private static final String TABLE_CELL = "table-cell";
    private static final String COVERED_TABLE_CELL = "covered-table-cell";
    private static final String PARAGRAPH = "p";

    private static final String NAME_ATTRIBUTE = "name";
    private static final String ROWS_REPEATED_ATTRIBUTE = "number-rows-repeated";
    private static final String COLUMNS_REPEATED_ATTRIBUTE = "number-columns-repeated";

    /** Au-delà, le fichier est forcément abîmé : un tableur ne dépasse pas 16 384 colonnes. */
    private static final int MAX_COLUMNS = 16_384;

    private final ZipFile archive;
    private final CountingInputStream content;
    private final XMLStreamReader xml;
    private final long contentSize;

    /** Numéro, dans la feuille, de la prochaine ligne à lire. */
    private long nextRowNumber = 1;

    /** Lignes identiques écrites une seule fois : il reste {@code repeatRemaining} fois ces valeurs. */
    private List<String> repeatValues = List.of();

    private long repeatRemaining;

    private OdsRowSource(ZipFile archive, CountingInputStream content, XMLStreamReader xml, long contentSize) {
        this.archive = archive;
        this.content = content;
        this.xml = xml;
        this.contentSize = contentSize;
    }

    /**
     * Ouvre le classeur, se place sur la feuille demandée et lit sa ligne d'en-tête.
     *
     * @throws ImportRejectedException si le classeur est illisible, la feuille introuvable ou la
     *     ligne d'en-tête absente
     */
    public static OdsRowSource open(Path file, SheetSettings settings) {
        ZipFile archive = null;
        CountingInputStream content = null;
        XMLStreamReader xml = null;
        try {
            archive = new ZipFile(file.toFile());
            var entry = archive.getEntry(CONTENT_ENTRY);
            if (entry == null) {
                throw new ImportRejectedException("import.error.classeur-illisible");
            }
            content = new CountingInputStream(archive.getInputStream(entry));
            xml = XmlReaders.create(content);
            var source = new OdsRowSource(archive, content, xml, entry.getSize());
            source.selectSheet(settings.sheetName());
            source.readHeader(settings);
            return source;
        } catch (ImportRejectedException e) {
            closeQuietly(xml, content, archive);
            throw e;
        } catch (IOException | XMLStreamException | RuntimeException e) {
            closeQuietly(xml, content, archive);
            throw new ImportRejectedException("import.error.classeur-illisible");
        }
    }

    /** Noms des feuilles du classeur, dans l'ordre : de quoi proposer le choix à l'analyste (IMP-02). */
    public static List<String> sheetNames(Path file) {
        try (var archive = new ZipFile(file.toFile())) {
            var entry = archive.getEntry(CONTENT_ENTRY);
            if (entry == null) {
                throw new ImportRejectedException("import.error.classeur-illisible");
            }
            try (var content = archive.getInputStream(entry)) {
                var xml = XmlReaders.create(content);
                var names = new ArrayList<String>();
                while (xml.hasNext()) {
                    if (xml.next() == XMLStreamConstants.START_ELEMENT && TABLE.equals(xml.getLocalName())) {
                        names.add(attribute(xml, NAME_ATTRIBUTE));
                    }
                }
                xml.close();
                return List.copyOf(names);
            }
        } catch (IOException | XMLStreamException | RuntimeException e) {
            throw new ImportRejectedException("import.error.classeur-illisible");
        }
    }

    @Override
    protected Row nextRow() {
        if (repeatRemaining > 0) {
            repeatRemaining--;
            return new Row(nextRowNumber++, repeatValues);
        }
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.END_ELEMENT && TABLE.equals(xml.getLocalName())) {
                    return null; // fin de la feuille demandée : les suivantes ne nous concernent pas
                }
                if (event == XMLStreamConstants.START_ELEMENT && TABLE_ROW.equals(xml.getLocalName())) {
                    long repeated = repetition(ROWS_REPEATED_ATTRIBUTE);
                    var values = readCells();
                    if (isEmpty(values)) {
                        nextRowNumber += repeated; // lignes vides : elles gardent leur numéro, rien de plus
                        continue;
                    }
                    repeatValues = values;
                    repeatRemaining = repeated - 1;
                    return new Row(nextRowNumber++, values);
                }
            }
            return null;
        } catch (XMLStreamException e) {
            throw new ImportRejectedException("import.error.ligne-illisible", nextRowNumber);
        }
    }

    @Override
    public int percent() {
        if (contentSize <= 0) {
            return 0;
        }
        return (int) Math.min(MAX_PERCENT_BEFORE_END, content.bytesRead() * 100 / contentSize);
    }

    @Override
    public void close() throws IOException {
        closeQuietly(xml, content, archive);
    }

    /** Avance jusqu'au début de la feuille demandée, ou de la première si aucune n'est précisée. */
    private void selectSheet(String sheetName) throws XMLStreamException {
        while (xml.hasNext()) {
            if (xml.next() == XMLStreamConstants.START_ELEMENT && TABLE.equals(xml.getLocalName())) {
                String name = attribute(xml, NAME_ATTRIBUTE);
                if (sheetName == null || sheetName.equalsIgnoreCase(name)) {
                    return;
                }
            }
        }
        throw sheetName == null
                ? new ImportRejectedException("import.error.classeur-sans-feuille")
                : new ImportRejectedException("import.error.feuille-introuvable", sheetName);
    }

    /**
     * Cellules de la ligne courante. Les cellules vides ne sont ajoutées que si une cellule remplie
     * les suit : sans cela, le millier de cellules vides qui termine chaque ligne d'un .ods
     * deviendrait autant de colonnes.
     */
    private List<String> readCells() throws XMLStreamException {
        var values = new ArrayList<String>();
        long emptyCells = 0;
        while (xml.hasNext()) {
            int event = xml.next();
            String name = event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT
                    ? xml.getLocalName()
                    : "";
            if (event == XMLStreamConstants.END_ELEMENT && TABLE_ROW.equals(name)) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT
                    && (TABLE_CELL.equals(name) || COVERED_TABLE_CELL.equals(name))) {
                long repeated = repetition(COLUMNS_REPEATED_ATTRIBUTE);
                String value = readCellText();
                if (value == null) {
                    emptyCells += repeated;
                    continue;
                }
                for (long skipped = 0; skipped < emptyCells; skipped++) {
                    values.add(null);
                }
                emptyCells = 0;
                for (long copy = 0; copy < repeated; copy++) {
                    values.add(value);
                }
                if (values.size() > MAX_COLUMNS) {
                    throw new ImportRejectedException("import.error.trop-de-colonnes", MAX_COLUMNS);
                }
            }
        }
        return values;
    }

    /** Texte affiché de la cellule courante : la suite de ses paragraphes, vide si elle n'en a pas. */
    private String readCellText() throws XMLStreamException {
        var text = new StringBuilder();
        int depth = 0;
        while (xml.hasNext()) {
            int event = xml.next();
            String name = event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT
                    ? xml.getLocalName()
                    : "";
            if (event == XMLStreamConstants.END_ELEMENT
                    && (TABLE_CELL.equals(name) || COVERED_TABLE_CELL.equals(name))) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT && PARAGRAPH.equals(name)) {
                depth++;
                if (!text.isEmpty()) {
                    text.append(' ');
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && PARAGRAPH.equals(name)) {
                depth--;
            } else if (event == XMLStreamConstants.CHARACTERS && depth > 0) {
                text.append(xml.getText());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** Facteur de répétition d'une ligne ou d'une cellule ; 1 par défaut. */
    private long repetition(String attributeName) {
        String value = attribute(xml, attributeName);
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(1, Long.parseLong(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Les attributs du format sont tous dans un espace de noms : on ne compare que le nom local. */
    private static String attribute(XMLStreamReader xml, String localName) {
        for (int index = 0; index < xml.getAttributeCount(); index++) {
            if (localName.equals(xml.getAttributeLocalName(index))) {
                return xml.getAttributeValue(index);
            }
        }
        return null;
    }

    private static void closeQuietly(XMLStreamReader xml, Closeable content, Closeable archive) {
        if (xml != null) {
            try {
                xml.close();
            } catch (XMLStreamException e) {
                // Lecture terminée : rien de plus à tenter.
            }
        }
        for (Closeable stream : new Closeable[] {content, archive}) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // Archive ouverte en lecture seule : rien de plus à tenter.
                }
            }
        }
    }
}
