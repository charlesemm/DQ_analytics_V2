package ci.cnam.dqanalyzer.importing.domain;

import ci.cnam.dqanalyzer.importing.ImportRejectedException;
import ci.cnam.dqanalyzer.importing.SheetSettings;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.SAXException;

/**
 * Lecture d'un classeur Excel 2007 et suivants (.xlsx, .xlsm) en flux : la feuille demandée est
 * parcourue cellule par cellule avec le lecteur XML du JDK, jamais ouverte en mémoire (PERF-03). Un
 * classeur d'un million de lignes se lit donc comme un classeur de dix lignes ; seule la table des
 * chaînes partagées du classeur, commune à toutes les feuilles, est chargée — c'est la contrepartie
 * du format.
 *
 * <p>La valeur retenue pour chaque cellule est celle qu'Excel affiche, mise en forme par le format
 * de la cellule : une date s'écrit « 31/12/2024 » et non « 45657 », ce que l'analyste doit retrouver
 * à l'identique dans le rapport d'anomalies (ERG-02).
 *
 * <p>Deux différences avec un fichier texte, propres au tableur : les lignes et les cellules vides
 * n'existent pas dans le fichier (une ligne de trois colonnes dont la dernière est vide n'est donc
 * pas une ligne incomplète), et le numéro de ligne est celui qu'affiche Excel.
 */
public final class XlsxRowSource extends SheetRowSource {

    private static final String ROW = "row";
    private static final String CELL = "c";
    private static final String VALUE = "v";
    private static final String TEXT = "t";
    private static final String INLINE_STRING = "is";
    private static final String DIMENSION = "dimension";
    private static final String SHEET_DATA = "sheetData";

    private static final String TYPE_SHARED_STRING = "s";
    private static final String TYPE_INLINE_STRING = "inlineStr";
    private static final String TYPE_FORMULA_STRING = "str";
    private static final String TYPE_BOOLEAN = "b";
    private static final String TYPE_ERROR = "e";

    private final OPCPackage workbook;
    private final InputStream sheet;
    private final XMLStreamReader xml;
    private final ReadOnlySharedStringsTable strings;
    private final StylesTable styles;
    private final DataFormatter formatter = new DataFormatter(Locale.FRANCE);

    /** Dernière ligne annoncée par la feuille, pour l'avancement ; 0 si la feuille ne le dit pas. */
    private final long lastRowNumber;

    private long lastReadRowNumber;

    private XlsxRowSource(
            OPCPackage workbook,
            InputStream sheet,
            XMLStreamReader xml,
            ReadOnlySharedStringsTable strings,
            StylesTable styles,
            long lastRowNumber) {
        this.workbook = workbook;
        this.sheet = sheet;
        this.xml = xml;
        this.strings = strings;
        this.styles = styles;
        this.lastRowNumber = lastRowNumber;
    }

    /**
     * Ouvre le classeur, se place sur la feuille demandée et lit sa ligne d'en-tête.
     *
     * @throws ImportRejectedException si le classeur est illisible, la feuille introuvable ou la
     *     ligne d'en-tête absente
     */
    public static XlsxRowSource open(Path file, SheetSettings settings) {
        OPCPackage workbook = null;
        InputStream sheet = null;
        XMLStreamReader xml = null;
        try {
            workbook = OPCPackage.open(file.toFile(), PackageAccess.READ);
            var strings = new ReadOnlySharedStringsTable(workbook);
            var reader = new XSSFReader(workbook);
            sheet = openSheet(reader, settings.sheetName());
            xml = XmlReaders.create(sheet);
            var source = new XlsxRowSource(workbook, sheet, xml, strings, reader.getStylesTable(), readDimension(xml));
            source.readHeader(settings);
            return source;
        } catch (ImportRejectedException e) {
            closeQuietly(xml, sheet, workbook);
            throw e;
        } catch (IOException | OpenXML4JException | SAXException | XMLStreamException e) {
            closeQuietly(xml, sheet, workbook);
            throw new ImportRejectedException("import.error.classeur-illisible");
        } catch (RuntimeException e) {
            // POI signale un fichier abîmé ou qui n'est pas un classeur par des exceptions non contrôlées.
            closeQuietly(xml, sheet, workbook);
            throw new ImportRejectedException("import.error.classeur-illisible");
        }
    }

    /** Noms des feuilles du classeur, dans l'ordre : de quoi proposer le choix à l'analyste (IMP-02). */
    public static List<String> sheetNames(Path file) {
        try (var workbook = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
            var sheets = (XSSFReader.SheetIterator) new XSSFReader(workbook).getSheetsData();
            var names = new ArrayList<String>();
            while (sheets.hasNext()) {
                try (var stream = sheets.next()) {
                    names.add(sheets.getSheetName());
                }
            }
            return List.copyOf(names);
        } catch (IOException | OpenXML4JException | RuntimeException e) {
            throw new ImportRejectedException("import.error.classeur-illisible");
        }
    }

    @Override
    public int percent() {
        var current = currentRow();
        if (lastRowNumber <= 0 || current == null) {
            return 0;
        }
        return (int) Math.min(MAX_PERCENT_BEFORE_END, current.number() * 100 / lastRowNumber);
    }

    @Override
    public void close() throws IOException {
        closeQuietly(xml, sheet, workbook);
    }

    /** Ligne suivante de la feuille, ou {@code null} à la fin de {@code sheetData}. */
    @Override
    protected Row nextRow() {
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT && ROW.equals(xml.getLocalName())) {
                    var row = readCells();
                    if (!isEmpty(row.values())) {
                        return row;
                    }
                }
                if (event == XMLStreamConstants.END_ELEMENT && SHEET_DATA.equals(xml.getLocalName())) {
                    return null;
                }
            }
            return null;
        } catch (XMLStreamException e) {
            throw new ImportRejectedException("import.error.ligne-illisible", lastReadRowNumber + 1);
        }
    }

    /** Cellules de la ligne courante, rangées à leur position ; les cellules absentes restent nulles. */
    private Row readCells() throws XMLStreamException {
        long number = rowNumberOf(attribute("r"));
        lastReadRowNumber = number;
        var values = new ArrayList<String>();
        int position = -1;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT && ROW.equals(xml.getLocalName())) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT && CELL.equals(xml.getLocalName())) {
                String type = attribute("t");
                String style = attribute("s");
                int index = columnIndex(attribute("r"));
                position = index < 0 ? position + 1 : index;
                String value = readCellValue(type, style);
                while (values.size() <= position) {
                    values.add(null);
                }
                values.set(position, value);
            }
        }
        return new Row(number, values);
    }

    /** Contenu d'une cellule : la valeur affichée par Excel, mise en forme par le format de la cellule. */
    private String readCellValue(String type, String style) throws XMLStreamException {
        String raw = null;
        var inline = new StringBuilder();
        boolean inInlineString = false;
        while (xml.hasNext()) {
            int event = xml.next();
            String name = event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT
                    ? xml.getLocalName()
                    : "";
            if (event == XMLStreamConstants.END_ELEMENT && CELL.equals(name)) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT && INLINE_STRING.equals(name)) {
                inInlineString = true;
            } else if (event == XMLStreamConstants.END_ELEMENT && INLINE_STRING.equals(name)) {
                inInlineString = false;
            } else if (event == XMLStreamConstants.START_ELEMENT && VALUE.equals(name)) {
                raw = xml.getElementText();
            } else if (event == XMLStreamConstants.START_ELEMENT && TEXT.equals(name) && inInlineString) {
                inline.append(xml.getElementText());
            }
        }
        if (TYPE_INLINE_STRING.equals(type)) {
            return inline.toString();
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (TYPE_SHARED_STRING.equals(type)) {
            return sharedString(raw);
        }
        if (TYPE_FORMULA_STRING.equals(type) || TYPE_ERROR.equals(type)) {
            return raw;
        }
        if (TYPE_BOOLEAN.equals(type)) {
            return "1".equals(raw) ? "VRAI" : "FAUX";
        }
        return formatted(raw, style);
    }

    private String sharedString(String index) {
        try {
            var item = strings.getItemAt(Integer.parseInt(index));
            return item == null ? null : item.getString();
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new ImportRejectedException("import.error.classeur-illisible");
        }
    }

    /** Applique le format de la cellule : dates, pourcentages et nombres s'écrivent comme à l'écran. */
    private String formatted(String raw, String style) {
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
        short formatIndex = 0;
        String formatString = null;
        if (style != null && styles != null) {
            try {
                var cellStyle = styles.getStyleAt(Integer.parseInt(style));
                if (cellStyle != null) {
                    formatIndex = cellStyle.getDataFormat();
                    formatString = cellStyle.getDataFormatString();
                }
            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                formatString = null; // style inconnu : on s'en tient au format général
            }
        }
        return formatter.formatRawCellContents(value, formatIndex, formatString);
    }

    private long rowNumberOf(String attribute) {
        if (attribute == null) {
            return lastReadRowNumber + 1;
        }
        try {
            return Long.parseLong(attribute);
        } catch (NumberFormatException e) {
            return lastReadRowNumber + 1;
        }
    }

    private String attribute(String name) {
        return xml.getAttributeValue(null, name);
    }

    /** Dernière ligne annoncée par {@code <dimension ref="A1:F2500"/>}, quand la feuille le précise. */
    private static long readDimension(XMLStreamReader xml) throws XMLStreamException {
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT && DIMENSION.equals(xml.getLocalName())) {
                return lastRowOf(xml.getAttributeValue(null, "ref"));
            }
            if (event == XMLStreamConstants.START_ELEMENT && SHEET_DATA.equals(xml.getLocalName())) {
                return 0;
            }
        }
        return 0;
    }

    private static long lastRowOf(String reference) {
        if (reference == null) {
            return 0;
        }
        int colon = reference.indexOf(':');
        String last = colon < 0 ? reference : reference.substring(colon + 1);
        var digits = new StringBuilder();
        for (int i = 0; i < last.length(); i++) {
            if (Character.isDigit(last.charAt(i))) {
                digits.append(last.charAt(i));
            }
        }
        try {
            return digits.isEmpty() ? 0 : Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** « C5 » donne 2 : la position de la colonne, à partir de 0 ; -1 si la référence manque. */
    private static int columnIndex(String cellReference) {
        if (cellReference == null) {
            return -1;
        }
        int index = 0;
        for (int i = 0; i < cellReference.length(); i++) {
            char letter = Character.toUpperCase(cellReference.charAt(i));
            if (letter < 'A' || letter > 'Z') {
                break;
            }
            index = index * 26 + (letter - 'A' + 1);
        }
        return index - 1;
    }

    private static InputStream openSheet(XSSFReader reader, String sheetName) throws IOException, OpenXML4JException {
        var sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
        while (sheets.hasNext()) {
            InputStream stream = sheets.next();
            if (sheetName == null || sheetName.equalsIgnoreCase(sheets.getSheetName())) {
                return stream;
            }
            stream.close();
        }
        throw sheetName == null
                ? new ImportRejectedException("import.error.classeur-sans-feuille")
                : new ImportRejectedException("import.error.feuille-introuvable", sheetName);
    }

    private static void closeQuietly(XMLStreamReader xml, InputStream sheet, Closeable workbook) {
        if (xml != null) {
            try {
                xml.close();
            } catch (XMLStreamException e) {
                // Lecture terminée : rien de plus à tenter.
            }
        }
        for (Closeable stream : new Closeable[] {sheet, workbook}) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // Idem : le classeur n'a été ouvert qu'en lecture.
                }
            }
        }
    }
}
