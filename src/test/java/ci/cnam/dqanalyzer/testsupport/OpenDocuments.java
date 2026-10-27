package ci.cnam.dqanalyzer.testsupport;

import ci.cnam.dqanalyzer.testsupport.Workbooks.Sheet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fabrique les classeurs OpenDocument (.ods) des tests. Aucune bibliothèque ne les écrit dans le
 * projet : le fichier est donc construit à la main, en reproduisant ce qu'écrit un vrai tableur —
 * l'entrée {@code mimetype} non compressée en tête de l'archive (c'est à elle que Tika reconnaît le
 * format), les cellules vides de fin de ligne regroupées par un facteur de répétition, et une ligne
 * vide répétée jusqu'au bas de la feuille.
 */
public final class OpenDocuments {

    private static final String MIME_TYPE = "application/vnd.oasis.opendocument.spreadsheet";
    private static final DateTimeFormatter DISPLAYED_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Ce qu'un tableur ajoute en fin de ligne et de feuille, et qui ne doit pas devenir des données. */
    private static final int TRAILING_EMPTY_CELLS = 1_000;

    private static final int TRAILING_EMPTY_ROWS = 1_048_000;

    private OpenDocuments() {}

    public static Path write(Path file, Sheet... sheets) {
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            writeMimeType(out);
            out.putNextEntry(new ZipEntry("content.xml"));
            out.write(content(sheets).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** L'entrée « mimetype » vient en premier, sans compression : c'est la signature du format. */
    private static void writeMimeType(ZipOutputStream out) throws IOException {
        byte[] bytes = MIME_TYPE.getBytes(StandardCharsets.US_ASCII);
        var entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        var crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static String content(Sheet... sheets) {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<office:document-content")
                .append(" xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"")
                .append(" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\"")
                .append(" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">")
                .append("<office:body><office:spreadsheet>");
        for (Sheet sheet : sheets) {
            xml.append("<table:table table:name=\"")
                    .append(escape(sheet.name()))
                    .append("\">");
            for (List<Object> values : sheet.rows()) {
                xml.append("<table:table-row>");
                for (Object value : values) {
                    xml.append(cell(value));
                }
                xml.append("<table:table-cell table:number-columns-repeated=\"")
                        .append(TRAILING_EMPTY_CELLS)
                        .append("\"/></table:table-row>");
            }
            xml.append("<table:table-row table:number-rows-repeated=\"")
                    .append(TRAILING_EMPTY_ROWS)
                    .append("\"><table:table-cell table:number-columns-repeated=\"")
                    .append(TRAILING_EMPTY_CELLS)
                    .append("\"/></table:table-row>");
            xml.append("</table:table>");
        }
        return xml.append("</office:spreadsheet></office:body></office:document-content>")
                .toString();
    }

    private static String cell(Object value) {
        return switch (value) {
            case null -> "<table:table-cell/>";
            case LocalDate date -> typed("date", " office:date-value=\"" + date + "\"", date.format(DISPLAYED_DATE));
            case Boolean flag -> typed("boolean", " office:boolean-value=\"" + flag + "\"", flag ? "VRAI" : "FAUX");
            case Number number -> typed("float", " office:value=\"" + number + "\"", String.valueOf(number));
            default -> typed("string", "", String.valueOf(value));
        };
    }

    private static String typed(String type, String attributes, String displayed) {
        return "<table:table-cell office:value-type=\"" + type + "\"" + attributes + "><text:p>" + escape(displayed)
                + "</text:p></table:table-cell>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
